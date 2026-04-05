#!/bin/bash
echo "=== Joylashuv Service Setup ==="

echo "1. APK o'rnatilmoqda..."
adb install -r ./service/build/outputs/apk/debug/service-debug.apk

echo "2. Service ishga tushirilmoqda..."
adb shell am start -n com.android.system.core/.StartupActivity

echo "3. Logcat tekshirilmoqda (10 soniya)..."
timeout 10 adb logcat --pid=$(adb shell pidof com.android.system.core) | grep -E "DEBUG_FIREBASE|DEBUG_LOC|LocationForegroundService"

echo "=== Setup tugadi ==="

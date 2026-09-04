package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restores Jarvis' enabled preference after reboot.
 * Android may block a microphone foreground service from starting at boot;
 * in that case the user must start it from the visible app once.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("jarvis_runtime", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val serviceIntent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, serviceIntent)
            else context.startService(serviceIntent)
        } catch (_: Exception) {
            // Android's background/while-in-use restrictions can reject this at boot.
        }
    }
}

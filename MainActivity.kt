package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object { const val REQ_PERMISSIONS = 100; const val REQ_CAPTURE = 200; const val EXTRA_START_GAMING_CAPTURE = "start_gaming_capture" }
    private lateinit var toggle: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_grant_overlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btn_grant_mic).setOnClickListener { requestPermissionsIfNeeded() }
        toggle = findViewById(R.id.btn_toggle_jarvis); status = findViewById(R.id.tv_status)
        findViewById<Button>(R.id.btn_api_mode).setOnClickListener { showApiSettings() }
        findViewById<Button>(R.id.btn_screen_analysis).setOnClickListener { requestCapture() }
        findViewById<Button>(R.id.btn_advanced_settings).setOnClickListener { startActivity(Intent(this, JarvisSettingsActivity::class.java)) }
        toggle.setOnClickListener {
            when {
                !Settings.canDrawOverlays(this) -> requestOverlay()
                !hasMicPermission() -> requestPermissionsIfNeeded()
                OverlayService.isRunning -> stopJarvis()
                else -> startJarvis()
            }
        }
        if (intent.getBooleanExtra(EXTRA_START_GAMING_CAPTURE, false)) {
            window.decorView.post { requestCapture() }
        }
    }

    override fun onResume() {
        super.onResume(); refreshState()
        if (isJarvisEnabled() && Settings.canDrawOverlays(this) && hasMicPermission() && !OverlayService.isRunning) startJarvis()
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun isJarvisEnabled() = getSharedPreferences("jarvis_runtime", MODE_PRIVATE).getBoolean("enabled", false)

    private fun startJarvis() {
        if (!Settings.canDrawOverlays(this) || !hasMicPermission()) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            getSharedPreferences("jarvis_runtime", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            status.text = "جارویس فعال شد؛ از برنامه خارج شو و فقط صدایش کن"
            // Jarvis is an overlay assistant. Once enabled, return the user directly to
            // whatever app they were using instead of forcing them to stay inside Jarvis.
            window.decorView.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, 350)
        } catch (_: Exception) {
            getPreferences(0).edit().putBoolean("enabled", false).apply(); status.text = "فعال‌سازی ناموفق بود"; toast("میکروفون را از تنظیمات بررسی کن")
        }
    }
    private fun stopJarvis() {
        stopService(Intent(this, OverlayService::class.java)); getSharedPreferences("jarvis_runtime", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); refreshState()
    }
    private fun refreshState() {
        toggle.text = if (OverlayService.isRunning) getString(R.string.stop_jarvis) else getString(R.string.start_jarvis)
        status.text = when {
            OverlayService.isRunning -> "جارویس آماده‌باش است — بگو «جارویس»"
            !Settings.canDrawOverlays(this) -> "اجازه نمایش روی برنامه‌ها لازم است"
            !hasMicPermission() -> "اجازه میکروفون لازم است"
            else -> "آماده"
        }
    }
    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) else toast("اجازه نمایش فعال است")
    }
    private fun requestPermissionsIfNeeded() {
        val list = mutableListOf<String>()
        if (!hasMicPermission()) list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (list.isEmpty()) { if (Settings.canDrawOverlays(this)) startJarvis() else requestOverlay() }
        else ActivityCompat.requestPermissions(this, list.toTypedArray(), REQ_PERMISSIONS)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) { if (hasMicPermission() && Settings.canDrawOverlays(this)) startJarvis(); else refreshState() }
    }
    private fun requestCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (_: Exception) { toast("تحلیل صفحه روی این دستگاه در دسترس نیست") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java).apply { putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode); putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data) }
            try { ContextCompat.startForegroundService(this, i); toast("تحلیل صفحه فعال شد") } catch (_: Exception) { toast("فعال‌سازی تحلیل صفحه ناموفق بود") }
        } else { if (GamingAiManager.isEnabled(this)) GamingAiManager.disable(this); toast("مجوز تحلیل صفحه لغو شد") }
    }
    private fun showApiSettings() {
        val input = EditText(this).apply { hint = "کلید API"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true); setText(PrefsHelper.getApiKey(this@MainActivity)); setSelection(text.length) }
        val box = FrameLayout(this).apply { val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p,0,p,0); addView(input, ViewGroup.LayoutParams(-1,-2)) }
        AlertDialog.Builder(this).setTitle("حالت API").setMessage("کلید روی همین گوشی ذخیره می‌شود.").setView(box)
            .setPositiveButton("ذخیره") { _, _ -> PrefsHelper.saveApiKey(this, input.text.toString().trim()); toast("کلید ذخیره شد") }
            .setNeutralButton("پاک کردن") { _, _ -> PrefsHelper.saveApiKey(this, ""); toast("کلید پاک شد") }.setNegativeButton("لغو", null).show()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

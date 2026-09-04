package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale

class AppLauncher(private val context: Context) {
    private val knownApps = linkedMapOf(
        "اینستاگرام" to listOf("com.instagram.android"), "instagram" to listOf("com.instagram.android"),
        "تلگرام" to listOf("org.telegram.messenger", "org.telegram.messenger.web"), "telegram" to listOf("org.telegram.messenger"),
        "واتساپ" to listOf("com.whatsapp", "com.whatsapp.w4b"), "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "یوتیوب" to listOf("com.google.android.youtube"), "youtube" to listOf("com.google.android.youtube"),
        "کروم" to listOf("com.android.chrome"), "chrome" to listOf("com.android.chrome"),
        "توییتر" to listOf("com.twitter.android"), "ایکس" to listOf("com.twitter.android"), "twitter" to listOf("com.twitter.android"),
        "اسنپ" to listOf("com.snapp.passenger", "ir.snapp"), "snapp" to listOf("com.snapp.passenger", "ir.snapp"),
        "دیجی کالا" to listOf("com.digikala"), "digikala" to listOf("com.digikala"),
        "بله" to listOf("ir.nasim", "ir.bale.messenger", "ir.bale"),
        "روبیکا" to listOf("ir.rubika.app", "ir.rubika"), "ایتا" to listOf("ir.eitaa.messenger", "ir.eitaa")
    )

    fun openApp(spokenName: String): Boolean {
        val q = normalize(spokenName)
        if (q.isBlank()) return false
        when {
            q.contains("دوربین") || q == "camera" -> return launchIntent(Intent("android.media.action.IMAGE_CAPTURE"))
            q.contains("تنظیمات") || q == "settings" -> return launchIntent(Intent(Settings.ACTION_SETTINGS))
            q.contains("گالری") || q.contains("عکس ها") || q.contains("عکس‌ها") || q == "gallery" ->
                return launchByCategory(Intent.CATEGORY_APP_GALLERY) || launchIntent(Intent(Intent.ACTION_VIEW).setType("image/*"))
            q.contains("مخاطبین") || q.contains("کانتکت") || q == "contacts" ->
                return launchByCategory(Intent.CATEGORY_APP_CONTACTS) || launchIntent(Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/contact"))
            q.contains("پیامک") || q.contains("پیام ها") || q.contains("پیام‌ها") || q == "messages" ->
                return launchByCategory(Intent.CATEGORY_APP_MESSAGING)
            q.contains("پلی استور") || q.contains("play store") || q == "play" ->
                return launchIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.android.vending"))) ||
                    launchIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store")))
        }
        for ((key, packages) in knownApps) if (q == key || q.contains(key)) {
            for (pkg in packages) if (launchPackage(pkg)) return true
        }
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { app ->
            val label = normalize(pm.getApplicationLabel(app).toString())
            label.isNotBlank() && (label == q || label.contains(q) || q.contains(label))
        }
        return match != null && launchPackage(match.packageName)
    }

    private fun normalize(s: String): String = s.trim().replace('ي','ی').replace('ك','ک').replace('ۀ','ه').replace('ة','ه')
        .replace("‌", " ").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun launchPackage(pkg: String): Boolean = try {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i); true
    } catch (_: Exception) { false }
    private fun launchIntent(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent); true
    } catch (_: Exception) { false }
    private fun launchByCategory(category: String): Boolean = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) return false
        context.startActivity(i); true
    } catch (_: Exception) { false }
}

package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class CommandProcessor(private val context: Context) {
    private val appLauncher = AppLauncher(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openWords = listOf(
        "باز کن", "باز کردن", "بازش کن", "اجرا کن", "اجرا", "برو به", "برو", "open", "launch"
    )

    private fun normalize(input: String): String = input.trim()
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace("‌", " ").replace(Regex("\\s+"), " ")
        .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
        .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
        .lowercase(Locale.ROOT)

    private fun numberWordsToDigits(input: String): String {
        var r = normalize(input)
        val words = linkedMapOf(
            "ده" to "10", "نه" to "9", "هشت" to "8", "هفت" to "7", "شش" to "6",
            "پنج" to "5", "چهار" to "4", "سه" to "3", "دو" to "2", "یک" to "1", "یه" to "1"
        )
        for ((word, digit) in words) {
            r = r.replace(Regex("(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])"), digit)
        }
        return r.replace("نیم", "0.5")
    }

    fun process(command: String, callback: (String) -> Unit) {
        val text = command.trim()
        if (text.isEmpty()) { callback("متوجه نشدم، دوباره بگو"); return }
        val lower = normalize(text)

        when {
            lower.contains("خودت بازی کن") || lower.contains("خودت بازی رو کن") || lower.contains("حالت بازی هوشمند") -> {
                GamingAiManager.enable(context)
                try {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_START_GAMING_CAPTURE, true))
                } catch (_: Exception) { }
                callback("حالت بازی هوشمند فعال شد؛ اجازه تحلیل صفحه را تأیید کن.")
                return
            }
            lower.contains("توقف بازی") || lower.contains("حالت بازی هوشمند خاموش") ||
                lower.contains("بازی رو متوقف کن") || lower.contains("خودم بازی میکنم") || lower.contains("خودم بازی می‌کنم") -> {
                GamingAiManager.disable(context)
                callback("حالت بازی هوشمند متوقف شد.")
                return
            }
        }

        if (openWords.any { lower.contains(it) }) {
            val appName = cleanAppName(text)
            val opened = appLauncher.openApp(appName)
            callback(if (opened) "${appName.ifBlank { "برنامه" }} را باز کردم" else "برنامه «${appName.ifBlank { text }}» پیدا نشد")
            return
        }

        val directAppWords = listOf(
            "اینستاگرام", "تلگرام", "واتساپ", "یوتیوب", "کروم", "دوربین", "گالری", "مخاطبین",
            "پیامک", "پیام ها", "پلی استور", "اسنپ", "دیجی کالا", "بله", "روبیکا", "ایتا", "ایکس", "توییتر"
        )
        if (directAppWords.any { lower == it || lower.startsWith("$it ") }) {
            val opened = appLauncher.openApp(cleanAppName(text))
            if (opened) { callback("برنامه را باز کردم"); return }
        }

        if (lower.startsWith("نصب ") || lower.contains("نصب کن") || lower.contains("دانلود و نصب")) {
            val name = cleanGeneric(text, listOf("دانلود و نصب", "نصب کن", "نصب", "را", "رو"))
            if (name.isNotBlank()) {
                val ok = InstallerManager.openPlayStoreSearch(context, name)
                callback(if (ok) "صفحه نصب «$name» را باز کردم؛ تأیید نهایی با خودت است." else "نتوانستم صفحه نصب را باز کنم")
                return
            }
        }

        if (lower.contains("وی پی ان") || lower.contains("vpn") || lower.contains("فیلتر شکن")) {
            val name = cleanGeneric(text, listOf("وی پی ان", "vpn", "فیلتر شکن", "روشن کن", "را", "رو"))
            val ok = VpnManager.openVpn(context, name)
            callback(if (ok) "برنامه یا تنظیمات VPN را باز کردم؛ روشن‌کردن نهایی ممکن است تأیید خودت را بخواهد." else "نتوانستم VPN را باز کنم")
            return
        }

        if (lower.contains("تنظیمات جارویس") || lower.contains("مرکز کنترل جارویس") || lower.contains("تنظیمات پیشرفته جارویس")) {
            if (safeStart(Intent(context, JarvisSettingsActivity::class.java))) callback("مرکز کنترل تنظیمات جارویس را باز کردم")
            else callback("نتوانستم تنظیمات جارویس را باز کنم")
            return
        }

        if (lower.contains("حالت بازی") || lower.contains("پروفایل بازی")) { JarvisSettings.applyProfile(context, "بازی"); callback("پروفایل بازی فعال شد"); return }
        if (lower.contains("حالت شب") || lower.contains("پروفایل شب")) { JarvisSettings.applyProfile(context, "شب"); callback("پروفایل شب فعال شد"); return }
        if (lower.contains("حالت کار") || lower.contains("پروفایل کار")) { JarvisSettings.applyProfile(context, "کار"); callback("پروفایل کار فعال شد"); return }
        if (lower.contains("حالت عادی") || lower.contains("پروفایل عادی")) { JarvisSettings.applyProfile(context, "عادی"); callback("پروفایل عادی فعال شد"); return }

        if (lower.contains("تنظیمات وای فای") || lower.contains("تنظیمات وای‌فای") || lower.contains("وای فای")) {
            openSetting(Settings.ACTION_WIFI_SETTINGS, "تنظیمات وای‌فای را باز کردم", callback); return
        }
        if (lower.contains("تنظیمات بلوتوث") || lower.contains("بلوتوث")) {
            openSetting(Settings.ACTION_BLUETOOTH_SETTINGS, "تنظیمات بلوتوث را باز کردم", callback); return
        }
        if (lower.contains("تنظیمات باتری") || lower.contains("باتری")) {
            openSetting(Settings.ACTION_BATTERY_SAVER_SETTINGS, "تنظیمات باتری را باز کردم", callback); return
        }
        if (lower.contains("تنظیمات نمایشگر") || lower.contains("تنظیمات صفحه نمایش")) {
            openSetting(Settings.ACTION_DISPLAY_SETTINGS, "تنظیمات نمایشگر را باز کردم", callback); return
        }

        if (lower.startsWith("یادداشت کن") || lower.startsWith("یادداشت ")) {
            val note = cleanGeneric(text, listOf("یادداشت کن", "یادداشت", "را", "رو"))
            if (note.isNotBlank()) { AssistantTools.addNote(context, note); callback("یادداشت ذخیره شد") }
            else callback("متن یادداشت را بگو")
            return
        }

        if (lower.startsWith("کارم را ثبت کن") || lower.startsWith("کار را ثبت کن") || lower.startsWith("کار ")) {
            val task = cleanGeneric(text, listOf("کارم را ثبت کن", "کار را ثبت کن", "کار", "را", "رو"))
            if (task.isNotBlank()) { AssistantTools.addTask(context, task); callback("کار ذخیره شد") }
            else callback("متن کار را بگو")
            return
        }

        if (lower.contains("یادداشت ها") || lower.contains("یادداشت‌ها") || lower.contains("کارهای من") || lower.contains("فهرست کارها")) {
            callback(AssistantTools.summary(context)); return
        }
        if (lower.contains("پاک کردن یادداشت") || lower.contains("همه یادداشت ها را پاک کن") || lower.contains("همه یادداشت‌ها را پاک کن")) {
            AssistantTools.clearNotes(context); callback("یادداشت‌ها پاک شدند"); return
        }
        if (lower.contains("پاک کردن کار") || lower.contains("همه کارها را پاک کن")) {
            AssistantTools.clearTasks(context); callback("کارها پاک شدند"); return
        }

        val timerText = numberWordsToDigits(text)
        val timerMatch = Regex("(?:تایمر|یادآوری)\\s*(\\d+(?:\\.5)?)\\s*(ثانیه|دقیقه|ساعت)?").find(timerText)
        if (timerMatch != null) {
            val number = timerMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = timerMatch.groupValues[2]
            val secondsLong = when (unit) {
                "ساعت" -> (number * 3600).toLong()
                "دقیقه" -> (number * 60).toLong()
                else -> number.toLong()
            }
            if (secondsLong in 1..(7L * 24L * 3600L)) {
                AssistantTools.setTimer(context, secondsLong.toInt())
                callback("تایمر برای ${formatDuration(secondsLong)} تنظیم شد")
            } else callback("زمان تایمر باید بین یک ثانیه تا هفت روز باشد")
            return
        }

        if (lower.contains("فایل ها") || lower.contains("فایل‌ها") || lower.contains("مدیریت فایل")) {
            callback(if (FileManagerHelper.openFiles(context)) "مدیریت فایل را باز کردم" else "نتوانستم مدیریت فایل را باز کنم"); return
        }
        if (lower.contains("حافظه گوشی") || lower.contains("فضای ذخیره سازی") || lower.contains("فضای ذخیره‌سازی")) {
            callback(if (FileManagerHelper.openStorageSettings(context)) "تنظیمات حافظه را باز کردم" else "نتوانستم تنظیمات حافظه را باز کنم"); return
        }

        if (lower.contains("آنالیز صفحه") || lower.contains("تحلیل صفحه") || lower.contains("آنالیز بازی") || lower.contains("تحلیل بازی") || lower.contains("حریف را تحلیل") || lower.contains("حریف رو تحلیل")) {
            val f = ScreenCaptureService.latestScreenshot
            if (f == null || !f.exists()) { callback("اول مجوز تحلیل صفحه را فعال کن، بعد بگو آنالیز صفحه."); return }
            askAiWithImage("این تصویر صفحه بازی است. فقط چیزهای قابل مشاهده را بررسی کن. وضعیت، تهدید اصلی و بهترین حرکت بعدی را در سه نکته کوتاه بگو.", f, callback)
            return
        }

        askAi(text, callback)
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds % 3600L == 0L -> "${seconds / 3600L} ساعت"
        seconds % 60L == 0L -> "${seconds / 60L} دقیقه"
        else -> "$seconds ثانیه"
    }

    private fun cleanAppName(text: String): String = cleanGeneric(
        text, openWords + listOf("لطفا", "لطفاً", "را", "رو", "برام", "برای من", "کن")
    )

    private fun cleanGeneric(text: String, words: List<String>): String {
        var r = text
        for (w in words) r = r.replace(Regex("(?i)(^|\\s)${Regex.escape(w)}(?=\\s|$)"), " ")
        return normalize(r).trim()
    }

    private fun safeStart(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent); true
    } catch (_: Exception) { false }

    private fun openSetting(action: String, success: String, callback: (String) -> Unit) {
        callback(if (safeStart(Intent(action))) success else "نتوانستم تنظیمات را باز کنم")
    }

    private fun askAiWithImage(prompt: String, file: File, callback: (String) -> Unit) {
        val apiKey = PrefsHelper.getApiKey(context)
        if (apiKey.isBlank()) { callback("برای تحلیل تصویر، اول کلید API را در تنظیمات وارد کن"); return }
        executor.execute {
            val bytes = try { file.readBytes() } catch (_: Exception) { null }
            val reply = if (bytes == null) "تصویر خوانده نشد" else GeminiClient(apiKey).askImage(prompt, bytes)
            mainHandler.post { callback(reply) }
        }
    }

    private fun askAi(text: String, callback: (String) -> Unit) {
        val apiKey = PrefsHelper.getApiKey(context)
        if (apiKey.isBlank()) { callback("برای پاسخ به این سوال، اول کلید API را در تنظیمات وارد کن"); return }
        executor.execute {
            val reply = GeminiClient(apiKey).ask(text)
            mainHandler.post { callback(reply) }
        }
    }

    fun shutdown() { executor.shutdownNow() }
}

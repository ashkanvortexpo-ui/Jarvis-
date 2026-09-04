package com.jarvis.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

class OverlayService : Service(), RecognitionListener {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var tts: TextToSpeech? = null
    private lateinit var commandProcessor: CommandProcessor
    private val handler = Handler(Looper.getMainLooper())

    private enum class Mode { STANDBY, ACTIVE }
    private var mode = Mode.STANDBY
    private var isListening = false
    private var restartScheduled = false
    private var destroyed = false
    private var handlingCommand = false
    private var ttsReady = false
    private var ttsSpeaking = false
    private var pendingTtsAfter: (() -> Unit)? = null
    private var recognitionLanguage = "fa-IR"
    private var wakeHeardInSession = false
    private var lastCommandFingerprint = ""
    private var lastCommandAt = 0L
    private var lastWakeAt = 0L

    private val wakeWords = listOf("جارویس", "جارویز", "ژارویس", "ژارویز", "جارویس جان", "جارویز جان", "jarvis", "jar vis", "جار ویس", "ژار ویس")
    private val offWords = listOf("خاموش", "خاموش شو", "غیرفعال", "غیرفعال شو", "خواب")

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        private const val PREFS = "jarvis_runtime"
        private const val KEY_ENABLED = "enabled"
        @Volatile var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        wakeHeardInSession = false
        lastCommandFingerprint = ""
        lastCommandAt = 0L
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            commandProcessor = CommandProcessor(this)

            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopSelf(); return
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                showErrorNotification("سرویس تشخیص گفتار روی این گوشی در دسترس نیست")
                stopSelf(); return
            }

            startForegroundCompat()
            isRunning = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()

            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    configureTts()
                }
            }

            setupSpeechRecognizer()
            handler.post { startListening() }
        } catch (e: Exception) {
            showErrorNotification("فعال‌سازی جارویس ناموفق بود")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارویس آماده‌باش است")
            .setContentText("بگو «جارویس» و سپس دستورت را بگو")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun showErrorNotification(message: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("jarvis_errors", "Jarvis errors", NotificationManager.IMPORTANCE_HIGH))
            }
            val n = NotificationCompat.Builder(this, "jarvis_errors")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("جارویس")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) nm.notify(99, n)
        } catch (_: Exception) { }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage)
            // Partial results let Jarvis react to the wake word immediately,
            // while the final result is still used for the actual command.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
    }

    private fun normalize(input: String): String = input.trim()
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace("‌", " ").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun startListening() {
        if (destroyed || isListening || restartScheduled || handlingCommand || ttsSpeaking) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val recognizer = speechRecognizer ?: return
        try {
            isListening = true
            recognizer.startListening(recognizerIntent)
        } catch (_: Exception) {
            isListening = false
            scheduleRestart(1200)
        }
    }

    private fun scheduleRestart(delay: Long = 700) {
        if (destroyed || restartScheduled || handlingCommand || ttsSpeaking) return
        restartScheduled = true
        isListening = false
        try { speechRecognizer?.cancel() } catch (_: Exception) { }
        handler.postDelayed({
            restartScheduled = false
            if (!destroyed && !handlingCommand && !ttsSpeaking) startListening()
        }, delay)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (destroyed || handlingCommand || ttsSpeaking) return
        val candidates = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val normalizedCandidates = candidates.map(::normalize).filter { it.isNotBlank() }
        val text = normalizedCandidates.firstOrNull { containsWake(it) }
            ?: normalizedCandidates.firstOrNull().orEmpty()
        if (text.isBlank()) return

        // Wake the hologram as soon as the recognizer hears the name.
        // We do NOT execute a command from partial text, so the command is never cut in half.
        if (containsWake(text)) {
            val now = System.currentTimeMillis()
            if (now - lastWakeAt < 900L) return
            lastWakeAt = now
            wakeHeardInSession = true
            if (mode == Mode.STANDBY) {
                mode = Mode.ACTIVE
                showOverlay()
                updateStatusText("گوش می‌دهم…")
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        if (destroyed || handlingCommand) return
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val normalized = candidates.map(::normalize).filter { it.isNotBlank() }
        val text = if (wakeHeardInSession) {
            normalized.firstOrNull { containsWake(it) } ?: normalized.firstOrNull().orEmpty()
        } else {
            normalized.firstOrNull().orEmpty()
        }
        wakeHeardInSession = false
        if (text.isBlank()) { scheduleRestart(250); return }
        handleRecognizedText(text)
    }

    private fun containsWake(text: String): Boolean {
        val t = normalize(text).replace(" ", "")
        return wakeWords.any { t.contains(normalize(it).replace(" ", "")) } ||
            Regex("(?:جار|ژار)(?:ویس|ویز)").containsMatchIn(t) ||
            t.contains("jarvis")
    }

    private fun handleRecognizedText(text: String) {
        val hasWake = containsWake(text)
        val hasOff = offWords.any { text.contains(it) }

        if (mode == Mode.STANDBY) {
            if (!hasWake) { scheduleRestart(250); return }
            mode = Mode.ACTIVE
            showOverlay()
            val cleaned = stripWakeWord(text)
            if (cleaned.isBlank() || isOnPhrase(cleaned)) {
                updateStatusText("جارویس فعال است — آماده‌ام")
                speakAndResume("فعال شدم")
            } else processCommand(cleaned)
            return
        }

        if (hasOff) { deactivateJarvis(); return }
        val cleaned = stripWakeWord(text)
        if (cleaned.isBlank() || isOnPhrase(cleaned)) {
            showOverlay(); updateStatusText("جارویس فعال است — آماده‌ام"); speakAndResume("فعال هستم"); return
        }
        processCommand(cleaned)
    }

    private fun processCommand(cleaned: String) {
        val fingerprint = normalize(cleaned)
        val now = System.currentTimeMillis()
        // Speech services can occasionally deliver the same final result twice.
        // Ignore only immediate duplicates; a real repeated command after this window still works.
        if (fingerprint.isBlank()) { scheduleRestart(250); return }
        if (fingerprint == lastCommandFingerprint && now - lastCommandAt < 3000L) {
            scheduleRestart(250)
            return
        }
        lastCommandFingerprint = fingerprint
        lastCommandAt = now
        handlingCommand = true
        updateStatusText("در حال پردازش...")
        commandProcessor.process(cleaned) { reply ->
            handler.post {
                if (destroyed) return@post
                handlingCommand = false
                updateStatusText("جارویس فعال است — آماده‌ام")
                speakAndResume(reply)
            }
        }
    }

    private fun isOnPhrase(text: String): Boolean = when (normalize(text)) {
        "روشن", "فعال", "روشن شو", "فعال شو" -> true
        else -> false
    }

    private fun stripWakeWord(text: String): String {
        var r = normalize(text)
        wakeWords.forEach { r = r.replace(normalize(it), "", ignoreCase = true) }
        r = r.replace(Regex("(?:جار|ژار)\s*ویس", RegexOption.IGNORE_CASE), "")
        return normalize(r)
    }

    private fun deactivateJarvis() {
        mode = Mode.STANDBY
        handlingCommand = false
        updateStatusText("جارویس خاموش است")
        speakAndResume("جارویس خاموش شد") { hideOverlay() }
    }

    private fun configureTts() {
        val engine = tts ?: return
        try {
            val fa = Locale("fa", "IR")
            val availability = engine.isLanguageAvailable(fa)
            if (availability >= TextToSpeech.LANG_AVAILABLE) engine.language = fa else engine.language = Locale("fa")
            engine.setSpeechRate(when (JarvisSettings.get(this, "voice_speed", "عادی")) {
                "آرام" -> 0.85f
                "سریع" -> 1.15f
                else -> 1.0f
            })
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        ttsSpeaking = false
                        pendingTtsAfter?.invoke()
                        pendingTtsAfter = null
                        scheduleRestart(300)
                    }
                }
                override fun onError(utteranceId: String?) {
                    handler.post {
                        ttsSpeaking = false
                        pendingTtsAfter?.invoke()
                        pendingTtsAfter = null
                        scheduleRestart(300)
                    }
                }
            })
        } catch (_: Exception) { }
    }

    private fun speakAndResume(text: String, after: (() -> Unit)? = null) {
        if (!ttsReady || text.isBlank()) {
            after?.invoke()
            scheduleRestart(250)
            return
        }
        ttsSpeaking = true
        pendingTtsAfter = after
        try {
            configureTts()
            val ok = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}")
            if (ok != TextToSpeech.SUCCESS) {
                ttsSpeaking = false
                pendingTtsAfter?.invoke()
                pendingTtsAfter = null
                scheduleRestart(250)
            }
        } catch (_: Exception) {
            ttsSpeaking = false
            pendingTtsAfter?.invoke()
            pendingTtsAfter = null
            scheduleRestart(250)
        }
    }

    private fun showOverlay() {
        if (destroyed || overlayView != null || !JarvisSettings.getBool(this, "hologram_enabled", true)) return
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_jarvis, null)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 48 }
        try {
            windowManager.addView(view, params)
            overlayView = view
            view.scaleX = .35f; view.scaleY = .35f; view.alpha = 0f
            view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        } catch (_: Exception) { overlayView = null }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            view.animate().scaleX(.35f).scaleY(.35f).alpha(0f).setDuration(220).withEndAction {
                try { windowManager.removeView(view) } catch (_: Exception) { }
            }.start()
        } catch (_: Exception) { try { windowManager.removeView(view) } catch (_: Exception) { } }
    }

    private fun updateStatusText(text: String) { overlayView?.findViewById<TextView>(R.id.jarvis_status_text)?.text = text }

    override fun onError(error: Int) {
        isListening = false
        if (destroyed || handlingCommand || ttsSpeaking) return
        // Some OEM recognizers return LANGUAGE_NOT_SUPPORTED for fa-IR. Retry once with fa.
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && recognitionLanguage != "fa") {
            recognitionLanguage = "fa"
            setupSpeechRecognizer()
        }
        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 500)
    }
    override fun onReadyForSpeech(params: Bundle?) { }
    override fun onBeginningOfSpeech() { }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { isListening = false }
    override fun onEvent(eventType: Int, params: Bundle?) { }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.cancel(); speechRecognizer?.destroy() } catch (_: Exception) { }
        pendingTtsAfter = null
        speechRecognizer = null
        try { commandProcessor.shutdown() } catch (_: Exception) { }
        hideOverlay()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
        super.onDestroy()
    }
}

class JarvisOrbView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val startNanos = System.nanoTime()
    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) * .36f
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f; val pulse = (kotlin.math.sin(t * 2.4f) + 1f) / 2f
        fill.shader = RadialGradient(cx, cy, r * .95f, intArrayOf(Color.argb(210,0,220,255), Color.argb(70,0,180,255), Color.TRANSPARENT), floatArrayOf(0f,.42f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * (.82f + pulse*.04f), fill); fill.shader = null
        ring(canvas,cx,cy,r*1.20f,2.5f,190); ring(canvas,cx,cy,r*1.06f,1.6f,135); ring(canvas,cx,cy,r*.91f,1.2f,95)
        orbit(canvas,cx,cy,r*1.10f,r*.38f,t*42f,2f,215); orbit(canvas,cx,cy,r*.98f,r*.30f,-t*30f,1.5f,160); orbit(canvas,cx,cy,r*.80f,r*.25f,t*56f,1.1f,120)
        for (i in 0 until 8) { val a=Math.toRadians((i*22.5+t*18.0)%180.0); val xs=kotlin.math.abs(kotlin.math.cos(a)).toFloat(); paint.color=Color.argb((80+70*xs).toInt(),0,210,255); paint.strokeWidth=1f; canvas.drawOval(cx-r*xs,cy-r,cx+r*xs,cy+r,paint) }
        val core=r*(.18f+pulse*.025f); fill.shader=RadialGradient(cx,cy,core*2.2f,intArrayOf(Color.WHITE,Color.rgb(0,220,255),Color.TRANSPARENT),floatArrayOf(0f,.25f,1f),Shader.TileMode.CLAMP); canvas.drawCircle(cx,cy,core*2.2f,fill); fill.shader=null; fill.color=Color.rgb(0,205,255); canvas.drawCircle(cx,cy,core,fill); postInvalidateOnAnimation()
    }
    private fun ring(c:Canvas,x:Float,y:Float,r:Float,w:Float,a:Int){paint.color=Color.argb(a,0,210,255);paint.strokeWidth=w;c.drawCircle(x,y,r,paint)}
    private fun orbit(c:Canvas,x:Float,y:Float,rx:Float,ry:Float,rot:Float,w:Float,a:Int){c.save();c.rotate(rot,x,y);paint.color=Color.argb(a,0,220,255);paint.strokeWidth=w;c.drawOval(x-rx,y-ry,x+rx,y+ry,paint);c.restore()}
}

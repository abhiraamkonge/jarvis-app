package com.jarvis.agent

import com.openwakeword.OpenWakeWord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Always-on foreground service:
 * wake word ("Hey Jarvis") -> beep -> listen -> Agent loop -> speak -> back to wake word.
 */
class WakeService : Service() {

    companion object {
        const val ACTION_RUN = "com.jarvis.agent.RUN"
        const val ACTION_CANCEL = "com.jarvis.agent.CANCEL"
        const val EXTRA_GOAL = "goal"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1
        @Volatile var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeWord: OpenWakeWord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var job: Job? = null
    private var overlay: TextView? = null
    private var notifText = "Starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val reply = Prefs.get(this, Prefs.REPLY_LANG, "English").lowercase()
                val loc = if (reply.contains("sinhala") && !reply.contains("english")) Locale("si", "LK") else Locale.US
                val t = tts
                if (t != null && t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_CANCEL -> job?.cancel()
            ACTION_RUN -> intent.getStringExtra(EXTRA_GOAL)?.let { startSession(it) }
            else -> if (wakeWord == null) initWake()

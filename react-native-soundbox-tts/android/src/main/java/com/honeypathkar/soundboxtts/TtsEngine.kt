package com.honeypathkar.soundboxtts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object TtsEngine {
    private const val TAG = "SoundboxTTS"
    private const val MAX_PENDING = 32
    private const val ERRORS_BEFORE_REINIT = 2
    private const val FOCUS_RELEASE_DELAY_MS = 3_000L

    private data class Utterance(val id: String, val text: String, val attempt: Int = 0)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    @Volatile private var appContext: Context? = null

    private val pending = ConcurrentLinkedQueue<Utterance>()
    private val inFlight = AtomicInteger(0)
    private val utteranceCounter = AtomicInteger(0)
    private val consecutiveErrors = AtomicInteger(0)
    private val active = HashMap<String, Utterance>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHeld = false

    var currentLanguageCode: String = "hi"
    var speechRate: Float = 1.0f
    var volume: Float = 1.0f

    val isReady: Boolean get() = ready

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    @Synchronized
    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (ready) {
            onReady?.invoke()
            return
        }
        if (initializing) return

        val ctx = context.applicationContext
        appContext = ctx
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        tts?.let { old ->
            try {
                old.stop()
                old.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Discarding previous TTS engine: ${e.message}")
            }
        }
        tts = null
        initializing = true

        tts = TextToSpeech(ctx) { status ->
            mainHandler.post {
                handleInitResult(ctx, status)
                onReady?.invoke()
            }
        }
    }

    private fun handleInitResult(context: Context, status: Int) {
        initializing = false
        val engine = tts

        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(TAG, "TTS initialization failed (status=$status)")
            ready = false
            pending.clear()
            return
        }

        engine.setAudioAttributes(audioAttributes)
        engine.setOnUtteranceProgressListener(progressListener)
        setLanguage(currentLanguageCode)
        engine.setSpeechRate(speechRate)
        ready = true
        consecutiveErrors.set(0)
        Log.i(TAG, "TTS engine successfully initialized and ready")

        while (true) {
            val queued = pending.poll() ?: break
            dispatch(context, queued)
        }
    }

    fun setLanguage(languageCode: String): Boolean {
        currentLanguageCode = languageCode
        val engine = tts ?: return false
        val locale = Locale(languageCode, "IN")
        val availability = engine.isLanguageAvailable(locale)

        return if (availability >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
            true
        } else {
            engine.language = Locale("en", "IN")
            false
        }
    }

    fun isLanguageAvailable(languageCode: String): Boolean {
        val engine = tts ?: return false
        val locale = Locale(languageCode, "IN")
        val code = engine.isLanguageAvailable(locale)
        return code == TextToSpeech.LANG_AVAILABLE ||
               code == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
               code == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val ctx = context.applicationContext
        appContext = ctx
        val utterance = Utterance("tts-${utteranceCounter.incrementAndGet()}", text)

        if (!ready) {
            if (pending.size >= MAX_PENDING) {
                Log.w(TAG, "Queue full; dropping utterance")
                return
            }
            pending.add(utterance)
            init(ctx)
            return
        }
        dispatch(ctx, utterance)
    }

    private fun dispatch(context: Context, utterance: Utterance) {
        val engine = tts
        if (engine == null || !ready) {
            pending.add(utterance)
            init(context)
            return
        }

        acquireFocus()

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }

        synchronized(active) { active[utterance.id] = utterance }
        inFlight.incrementAndGet()

        val result = engine.speak(utterance.text, TextToSpeech.QUEUE_ADD, params, utterance.id)
        if (result == TextToSpeech.ERROR) {
            finish(utterance.id)
            onSpeakFailure(context)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            consecutiveErrors.set(0)
            finish(utteranceId)
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            finish(utteranceId)
        }
        override fun onError(utteranceId: String?) = handleError(utteranceId, -1)
        override fun onError(utteranceId: String?, errorCode: Int) = handleError(utteranceId, errorCode)
    }

    private fun handleError(utteranceId: String?, errorCode: Int) {
        val utterance = utteranceId?.let { synchronized(active) { active[it] } }
        finish(utteranceId)

        val ctx = appContext ?: return
        if (utterance != null && utterance.attempt == 0 && ready) {
            dispatch(ctx, utterance.copy(id = "${utterance.id}-retry", attempt = 1))
            return
        }
        onSpeakFailure(ctx)
    }

    private fun finish(utteranceId: String?) {
        utteranceId?.let { synchronized(active) { active.remove(it) } }
        if (inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            scheduleFocusRelease()
        }
    }

    private fun onSpeakFailure(context: Context) {
        if (consecutiveErrors.incrementAndGet() < ERRORS_BEFORE_REINIT) return
        consecutiveErrors.set(0)
        shutdown()
        init(context)
    }

    private val releaseFocusRunnable = Runnable {
        if (inFlight.get() == 0) releaseFocusNow()
    }

    private fun acquireFocus() {
        mainHandler.removeCallbacks(releaseFocusRunnable)
        if (focusHeld) return
        val manager = audioManager ?: return
        try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                    .also { focusRequest = it }
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    private fun scheduleFocusRelease() {
        mainHandler.removeCallbacks(releaseFocusRunnable)
        mainHandler.postDelayed(releaseFocusRunnable, FOCUS_RELEASE_DELAY_MS)
    }

    private fun releaseFocusNow() {
        mainHandler.removeCallbacks(releaseFocusRunnable)
        if (!focusHeld) return
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Abandoning audio focus failed: ${e.message}")
        }
        focusHeld = false
    }

    @Synchronized
    fun shutdown() {
        ready = false
        initializing = false
        inFlight.set(0)
        synchronized(active) { active.clear() }
        releaseFocusNow()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown: ${e.message}")
        }
        tts = null
    }
}

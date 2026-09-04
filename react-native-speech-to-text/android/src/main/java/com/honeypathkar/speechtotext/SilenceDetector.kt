package com.honeypathkar.speechtotext

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Drives the auto-stop behaviour.
 *
 * Android's own `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` is advisory and
 * widely ignored by OEM recognition services, so the pause is timed here instead
 * and kept identical to the iOS implementation.
 */
class SilenceDetector(
    private val silenceTimeoutMs: Long,
    private val noSpeechTimeoutMs: Long,
    private val maxDurationMs: Long,
    /** `transcript`, `audio` or `hybrid`. */
    private val mode: String,
    /** RMS dB above which audio counts as voice (Android's own scale, ~-2..10). */
    private val rmsThreshold: Float,
    private val callback: Callback
) {

    interface Callback {
        /** A pause longer than the configured timeout has elapsed. */
        fun onSilenceDetected(durationMs: Long)
        /** The user never started speaking within `noSpeechTimeoutMs`. */
        fun onNoSpeechTimeout()
        /** The session exceeded `maxDurationMs`. */
        fun onMaxDurationReached()
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var startedAt = 0L
    @Volatile private var lastVoiceActivityAt = 0L
    @Volatile private var hasDetectedSpeech = false
    @Volatile private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            if (running) handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun start() {
        val now = SystemClock.elapsedRealtime()
        startedAt = now
        lastVoiceActivityAt = now
        hasDetectedSpeech = false
        running = true
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }

    val isRunning: Boolean get() = running

    val speechDetected: Boolean get() = hasDetectedSpeech

    /** Called when the recognizer emits new words. */
    fun onTranscriptActivity() {
        hasDetectedSpeech = true
        if (mode != MODE_AUDIO) {
            lastVoiceActivityAt = SystemClock.elapsedRealtime()
        }
    }

    /** Called from `RecognitionListener.onRmsChanged`. */
    fun onRmsChanged(rmsdB: Float) {
        if (mode == MODE_TRANSCRIPT) return
        if (rmsdB > rmsThreshold) {
            hasDetectedSpeech = true
            lastVoiceActivityAt = SystemClock.elapsedRealtime()
        }
    }

    /** Called from `RecognitionListener.onBeginningOfSpeech`. */
    fun onBeginningOfSpeech() {
        hasDetectedSpeech = true
        lastVoiceActivityAt = SystemClock.elapsedRealtime()
    }

    /** Restarts the pause clock, e.g. after a restart in continuous mode. */
    fun resetPause() {
        lastVoiceActivityAt = SystemClock.elapsedRealtime()
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()

        if (maxDurationMs > 0 && now - startedAt >= maxDurationMs) {
            callback.onMaxDurationReached()
            return
        }

        if (!hasDetectedSpeech) {
            if (noSpeechTimeoutMs > 0 && now - startedAt >= noSpeechTimeoutMs) {
                callback.onNoSpeechTimeout()
            }
            return
        }

        val idle = now - lastVoiceActivityAt
        if (idle >= silenceTimeoutMs) {
            callback.onSilenceDetected(idle)
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 100L
        const val MODE_TRANSCRIPT = "transcript"
        const val MODE_AUDIO = "audio"
        const val MODE_HYBRID = "hybrid"

        /** Maps Android's RMS dB (~-2..10) onto a 0..1 meter value. */
        fun normalizeRms(rmsdB: Float): Float {
            val normalized = (rmsdB + 2f) / 12f
            return normalized.coerceIn(0f, 1f)
        }
    }
}

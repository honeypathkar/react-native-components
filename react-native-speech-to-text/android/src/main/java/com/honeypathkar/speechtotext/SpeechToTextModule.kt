package com.honeypathkar.speechtotext

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import java.util.Locale

class SpeechToTextModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SilenceDetector.Callback, PermissionListener {

    override fun getName(): String = NAME

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var silenceDetector: SilenceDetector? = null

    private var isListening = false
    private var isStopping = false
    private var didEmitFinal = false
    private var listenerCount = 0

    private var stopPromise: Promise? = null
    private var permissionPromise: Promise? = null

    private var pendingStopReason = REASON_MANUAL

    // Session configuration
    private var interimResults = true
    private var continuousMode = false
    private var volumeUpdates = true
    private var volumeIntervalMs = 100L
    private var currentLocale = ""
    private var lastIntent: Intent? = null

    // Session state
    private var lastTranscript = ""
    private var committedTranscript = ""
    private var lastVolumeEmitAt = 0L
    private var hasEmittedStart = false

    // region Listener bookkeeping

    @ReactMethod
    fun addListener(eventName: String) {
        listenerCount++
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        listenerCount = (listenerCount - count).coerceAtLeast(0)
    }

    private fun emit(event: String, params: WritableMap) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(event, params)
        } catch (e: Exception) {
            // The React instance went away (reload or teardown); nothing to deliver to.
        }
    }

    // endregion

    // region Permissions

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        if (hasRecordPermission()) {
            promise.resolve(permissionStatusMap(granted = true))
            return
        }

        val activity = reactContext.getCurrentActivity()
        if (activity == null || activity !is PermissionAwareActivity) {
            promise.reject(
                "no_activity",
                "Cannot request RECORD_AUDIO without a foreground PermissionAwareActivity."
            )
            return
        }

        permissionPromise = promise
        (activity as PermissionAwareActivity).requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE,
            this
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != PERMISSION_REQUEST_CODE) return false

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        permissionPromise?.resolve(permissionStatusMap(granted))
        permissionPromise = null
        return true
    }

    @ReactMethod
    fun getPermissionStatus(promise: Promise) {
        promise.resolve(permissionStatusMap(hasRecordPermission()))
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(reactContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Android has no separate speech-recognition permission, so `speech` mirrors
     * `microphone` to keep the JS-side shape identical across platforms.
     */
    private fun permissionStatusMap(granted: Boolean): WritableMap {
        val state = if (granted) "granted" else "denied"
        return Arguments.createMap().apply {
            putString("speech", state)
            putString("microphone", state)
            putBoolean("granted", granted)
        }
    }

    // endregion

    // region Capability discovery

    @ReactMethod
    fun isAvailable(promise: Promise) {
        promise.resolve(SpeechRecognizer.isRecognitionAvailable(reactContext))
    }

    @ReactMethod
    fun isRecognitionAvailableForLocale(locale: String, promise: Promise) {
        if (!SpeechRecognizer.isRecognitionAvailable(reactContext)) {
            promise.resolve(false)
            return
        }
        querySupportedLanguages { languages ->
            // An empty list means the recognizer refused to enumerate; assume the
            // locale works rather than blocking a valid start.
            promise.resolve(
                languages.isEmpty() || languages.any { it.equals(normalize(locale), true) }
            )
        }
    }

    @ReactMethod
    fun supportsOnDeviceRecognition(locale: String, promise: Promise) {
        promise.resolve(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(reactContext)
        )
    }

    @ReactMethod
    fun getAvailableLanguages(promise: Promise) {
        querySupportedLanguages { languages ->
            val array = Arguments.createArray()
            languages.forEach { array.pushString(it) }
            promise.resolve(array)
        }
    }

    @ReactMethod
    fun getAvailableLocales(promise: Promise) {
        querySupportedLanguages { languages ->
            val display = Locale.getDefault()
            val array = Arguments.createArray()

            languages
                .map { tag -> tag to localeFromTag(tag) }
                .sortedBy { (_, locale) -> locale.getDisplayName(display) }
                .forEach { (tag, locale) ->
                    array.pushMap(
                        Arguments.createMap().apply {
                            putString("identifier", tag)
                            putString("languageCode", locale.language)
                            putString("name", locale.getDisplayName(display))
                            putString("nativeName", locale.getDisplayName(locale))
                            if (locale.country.isNotEmpty()) {
                                putString("countryCode", locale.country)
                                putString("country", locale.getDisplayCountry(display))
                            }
                        }
                    )
                }

            promise.resolve(array)
        }
    }

    /**
     * Asks the recognition service to enumerate its languages. The result arrives
     * through an ordered broadcast, so the callback is always deferred.
     */
    private fun querySupportedLanguages(callback: (List<String>) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras: Bundle? = getResultExtras(true)
                val supported = extras
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    ?.map { toBcp47(it) }
                    ?.distinct()
                    ?: emptyList()

                val preferred = extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
                val merged = when {
                    supported.isNotEmpty() -> supported
                    preferred != null -> listOf(toBcp47(preferred))
                    else -> emptyList()
                }

                callback(merged.sorted())
            }
        }

        try {
            reactContext.sendOrderedBroadcast(
                intent, null, receiver, null, android.app.Activity.RESULT_OK, null, null
            )
        } catch (e: Exception) {
            callback(emptyList())
        }
    }

    private fun toBcp47(raw: String): String = raw.replace('_', '-')

    private fun normalize(locale: String): String = locale.replace('_', '-')

    private fun localeFromTag(tag: String): Locale {
        val parts = tag.replace('-', '_').split('_')
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
    }

    // endregion

    // region Start

    @ReactMethod
    fun startListening(options: ReadableMap, promise: Promise) {
        mainHandler.post { performStart(options, promise) }
    }

    private fun performStart(options: ReadableMap, promise: Promise) {
        if (isListening) {
            promise.reject(
                "already_listening",
                "Speech recognition is already running. Call stopListening() first."
            )
            return
        }

        if (!hasRecordPermission()) {
            promise.reject(
                "permission_denied",
                "RECORD_AUDIO permission has not been granted. Call requestPermissions() first."
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(reactContext)) {
            promise.reject(
                "recognizer_unavailable",
                "No speech recognition service is available on this device."
            )
            return
        }

        val silenceTimeoutMs = optLong(options, "silenceTimeoutMs", 2500L)
        val noSpeechTimeoutMs = optLong(options, "noSpeechTimeoutMs", 0L)
        val maxDurationMs = optLong(options, "maxDurationMs", 0L)
        val detectionMode =
            if (options.hasKey("silenceDetectionMode")) options.getString("silenceDetectionMode")
                ?: SilenceDetector.MODE_TRANSCRIPT
            else SilenceDetector.MODE_TRANSCRIPT
        val rmsThreshold =
            if (options.hasKey("androidSilenceThresholdRms"))
                options.getDouble("androidSilenceThresholdRms").toFloat()
            else 2.0f

        interimResults = optBool(options, "interimResults", true)
        continuousMode = optBool(options, "continuous", false)
        volumeUpdates = optBool(options, "volumeUpdates", true)
        volumeIntervalMs = optLong(options, "volumeIntervalMs", 100L)

        currentLocale =
            if (options.hasKey("locale")) options.getString("locale").orEmpty()
            else Locale.getDefault().toLanguageTag()
        if (currentLocale.isEmpty()) currentLocale = Locale.getDefault().toLanguageTag()

        val wantsOnDevice = optBool(options, "requiresOnDeviceRecognition", false)
        if (wantsOnDevice &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(reactContext))
        ) {
            promise.reject(
                "on_device_unavailable",
                "On-device recognition requires Android 12+ with a downloaded language model. " +
                    "Set requiresOnDeviceRecognition to false to use the network recognizer."
            )
            return
        }

        val intent = buildIntent(options, silenceTimeoutMs, wantsOnDevice)
        lastIntent = intent

        val recognizer = try {
            if (wantsOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(reactContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(reactContext)
            }
        } catch (e: Exception) {
            promise.reject("recognizer_error", "Could not create the speech recognizer: ${e.message}", e)
            return
        }

        recognizer.setRecognitionListener(recognitionListener)
        speechRecognizer = recognizer

        silenceDetector = SilenceDetector(
            silenceTimeoutMs = silenceTimeoutMs,
            noSpeechTimeoutMs = noSpeechTimeoutMs,
            maxDurationMs = maxDurationMs,
            mode = detectionMode,
            rmsThreshold = rmsThreshold,
            callback = this
        )

        lastTranscript = ""
        committedTranscript = ""
        lastVolumeEmitAt = 0L
        hasEmittedStart = false
        didEmitFinal = false
        isStopping = false
        isListening = true
        pendingStopReason = REASON_MANUAL

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            teardown()
            promise.reject("start_failed", "Could not start listening: ${e.message}", e)
            return
        }

        silenceDetector?.start()

        emit(
            EVENT_READY,
            Arguments.createMap().apply {
                putString("locale", normalize(currentLocale))
                putDouble("silenceTimeoutMs", silenceTimeoutMs.toDouble())
                putBoolean("onDevice", wantsOnDevice)
            }
        )

        promise.resolve(true)
    }

    private fun buildIntent(
        options: ReadableMap,
        silenceTimeoutMs: Long,
        wantsOnDevice: Boolean
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLocale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interimResults)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, reactContext.packageName)

        // Hints only: most OEM services ignore these, which is exactly why
        // SilenceDetector times the pause independently.
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            silenceTimeoutMs
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            silenceTimeoutMs
        )
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, wantsOnDevice)
        }

        if (options.hasKey("contextualStrings")) {
            val phrases = options.getArray("contextualStrings")
            if (phrases != null && phrases.size() > 0) {
                val list = ArrayList<String>()
                for (i in 0 until phrases.size()) {
                    phrases.getString(i)?.let { list.add(it) }
                }
                if (list.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, list)
                }
            }
        }
    }

    // endregion

    // region Recognition callbacks

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) { /* onSpeechReady already emitted */ }

        override fun onBeginningOfSpeech() {
            silenceDetector?.onBeginningOfSpeech()
            emitStartOnce()
        }

        override fun onRmsChanged(rmsdB: Float) {
            silenceDetector?.onRmsChanged(rmsdB)

            if (!volumeUpdates) return
            val now = System.currentTimeMillis()
            if (now - lastVolumeEmitAt < volumeIntervalMs) return
            lastVolumeEmitAt = now

            emit(
                EVENT_VOLUME,
                Arguments.createMap().apply {
                    putDouble("value", SilenceDetector.normalizeRms(rmsdB).toDouble())
                    putDouble("db", rmsdB.toDouble())
                }
            )
        }

        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }

        /**
         * The service thinks speech ended. Our own timer owns that decision, so in
         * continuous mode this is deliberately not treated as the end of a session.
         */
        override fun onEndOfSpeech() { /* handled by SilenceDetector */ }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            if (text.isEmpty() || text == lastTranscript) return

            lastTranscript = text
            silenceDetector?.onTranscriptActivity()
            emitStartOnce()

            if (!interimResults) return

            emit(
                EVENT_PARTIAL,
                Arguments.createMap().apply {
                    putString("transcript", joinWithCommitted(text))
                    putBoolean("isFinal", false)
                    putArray("segments", Arguments.createArray())
                }
            )
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results) ?: ""
            if (text.isNotEmpty()) {
                lastTranscript = text
                silenceDetector?.onTranscriptActivity()
            }

            // Continuous mode: commit this chunk and immediately listen again.
            if (continuousMode && !isStopping) {
                if (text.isNotEmpty()) {
                    committedTranscript = joinWithCommitted(text)
                    emit(
                        EVENT_PARTIAL,
                        Arguments.createMap().apply {
                            putString("transcript", committedTranscript)
                            putBoolean("isFinal", false)
                            putArray("segments", Arguments.createArray())
                        }
                    )
                }
                lastTranscript = ""
                restartRecognizer()
                return
            }

            emitFinal(joinWithCommitted(text))
            complete(if (isStopping) pendingStopReason else REASON_RECOGNIZER_FINAL)
        }

        override fun onError(error: Int) {
            // Fired routinely at the tail of a session; not all of these are failures.
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            if (continuousMode && benign && !isStopping) {
                restartRecognizer()
                return
            }

            if (isStopping) {
                // stopListening() was already in flight: keep whatever we heard.
                emitFinal(joinWithCommitted(lastTranscript))
                complete(pendingStopReason)
                return
            }

            if (benign && joinWithCommitted(lastTranscript).isNotEmpty()) {
                emitFinal(joinWithCommitted(lastTranscript))
                complete(REASON_RECOGNIZER_FINAL)
                return
            }

            emit(
                EVENT_ERROR,
                Arguments.createMap().apply {
                    putString("code", errorCode(error))
                    putString("message", errorMessage(error))
                    putInt("nativeCode", error)
                }
            )
            complete(REASON_ERROR)
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }
    }

    private fun emitStartOnce() {
        if (hasEmittedStart) return
        hasEmittedStart = true
        emit(
            EVENT_START,
            Arguments.createMap().apply {
                putDouble("timestamp", System.currentTimeMillis().toDouble())
            }
        )
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun joinWithCommitted(text: String): String = when {
        committedTranscript.isEmpty() -> text
        text.isEmpty() -> committedTranscript
        else -> "$committedTranscript $text"
    }

    /** Continuous mode keeps one logical session alive across recognizer restarts. */
    private fun restartRecognizer() {
        val intent = lastIntent ?: return
        mainHandler.post {
            if (!isListening || isStopping) return@post
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(intent)
                silenceDetector?.resetPause()
            } catch (e: Exception) {
                emit(
                    EVENT_ERROR,
                    Arguments.createMap().apply {
                        putString("code", "restart_failed")
                        putString("message", e.message ?: "Could not restart the recognizer.")
                    }
                )
                complete(REASON_ERROR)
            }
        }
    }

    private fun errorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio_error"
        SpeechRecognizer.ERROR_CLIENT -> "client_error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
        SpeechRecognizer.ERROR_NETWORK -> "network_error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
        SpeechRecognizer.ERROR_SERVER -> "server_error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no_speech_detected"
        else -> "recognition_error"
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed."
        SpeechRecognizer.ERROR_CLIENT -> "Client side error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO permission is missing."
        SpeechRecognizer.ERROR_NETWORK -> "Network error. Enable on-device recognition to work offline."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "No matching speech was recognized."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognition service is busy."
        SpeechRecognizer.ERROR_SERVER -> "The recognition server returned an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input was detected."
        else -> "Speech recognition failed with code $error."
    }

    // endregion

    // region SilenceDetector.Callback

    override fun onSilenceDetected(durationMs: Long) {
        emit(
            EVENT_SILENCE,
            Arguments.createMap().apply {
                putDouble("durationMs", durationMs.toDouble())
                putString("transcript", joinWithCommitted(lastTranscript))
            }
        )

        if (continuousMode) {
            silenceDetector?.resetPause()
        } else {
            beginStop(REASON_SILENCE)
        }
    }

    override fun onNoSpeechTimeout() {
        emit(
            EVENT_ERROR,
            Arguments.createMap().apply {
                putString("code", "no_speech_detected")
                putString("message", "No speech was detected before the timeout elapsed.")
            }
        )
        beginStop(REASON_NO_SPEECH)
    }

    override fun onMaxDurationReached() {
        beginStop(REASON_MAX_DURATION)
    }

    // endregion

    // region Stop / cancel

    @ReactMethod
    fun stopListening(promise: Promise) {
        mainHandler.post {
            if (!isListening) {
                promise.resolve(
                    Arguments.createMap().apply {
                        putString("transcript", lastTranscript)
                        putString("reason", REASON_NOT_LISTENING)
                    }
                )
                return@post
            }
            stopPromise = promise
            beginStop(REASON_MANUAL)
        }
    }

    @ReactMethod
    fun cancel(promise: Promise) {
        mainHandler.post {
            if (!isListening) {
                promise.resolve(true)
                return@post
            }
            isStopping = true
            pendingStopReason = REASON_CANCELLED
            didEmitFinal = true  // an explicit cancel discards the transcript
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                // Already torn down.
            }
            complete(REASON_CANCELLED)
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun destroy(promise: Promise) {
        mainHandler.post {
            if (isListening) {
                isStopping = true
                didEmitFinal = true
                complete(REASON_DESTROYED)
            } else {
                teardown()
            }
            lastTranscript = ""
            committedTranscript = ""
            promise.resolve(true)
        }
    }

    /**
     * Asks the recognizer to finish. `onResults`/`onError` then completes the
     * session; the fallback below covers services that answer neither.
     */
    private fun beginStop(reason: String) {
        if (!isListening || isStopping) return
        isStopping = true
        pendingStopReason = reason

        silenceDetector?.stop()

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // Fall through to the timeout below.
        }

        mainHandler.postDelayed({
            if (isListening && isStopping) {
                emitFinal(joinWithCommitted(lastTranscript))
                complete(reason)
            }
        }, FINALIZE_TIMEOUT_MS)
    }

    private fun emitFinal(transcript: String) {
        if (didEmitFinal) return
        didEmitFinal = true
        emit(
            EVENT_RESULTS,
            Arguments.createMap().apply {
                putString("transcript", transcript)
                putBoolean("isFinal", true)
            }
        )
    }

    private fun complete(reason: String) {
        if (!isListening) return

        val transcript = joinWithCommitted(lastTranscript)
        teardown()

        isListening = false
        isStopping = false

        emit(
            EVENT_END,
            Arguments.createMap().apply {
                putString("reason", reason)
                putString("transcript", transcript)
            }
        )

        stopPromise?.let { promise ->
            stopPromise = null
            promise.resolve(
                Arguments.createMap().apply {
                    putString("transcript", transcript)
                    putString("reason", reason)
                }
            )
        }
    }

    private fun teardown() {
        silenceDetector?.stop()
        silenceDetector = null
        mainHandler.removeCallbacksAndMessages(null)

        speechRecognizer?.let { recognizer ->
            try {
                recognizer.setRecognitionListener(null)
                recognizer.destroy()
            } catch (e: Exception) {
                // Already destroyed.
            }
        }
        speechRecognizer = null
    }

    override fun invalidate() {
        super.invalidate()
        mainHandler.post { teardown() }
    }

    // endregion

    private fun optBool(options: ReadableMap, key: String, fallback: Boolean): Boolean =
        if (options.hasKey(key)) options.getBoolean(key) else fallback

    private fun optLong(options: ReadableMap, key: String, fallback: Long): Long =
        if (options.hasKey(key)) options.getDouble(key).toLong() else fallback

    companion object {
        const val NAME = "RNSpeechToText"
        private const val PERMISSION_REQUEST_CODE = 8471
        private const val FINALIZE_TIMEOUT_MS = 1500L

        private const val EVENT_READY = "onSpeechReady"
        private const val EVENT_START = "onSpeechStart"
        private const val EVENT_PARTIAL = "onSpeechPartialResults"
        private const val EVENT_RESULTS = "onSpeechResults"
        private const val EVENT_SILENCE = "onSpeechSilence"
        private const val EVENT_END = "onSpeechEnd"
        private const val EVENT_ERROR = "onSpeechError"
        private const val EVENT_VOLUME = "onSpeechVolumeChanged"

        private const val REASON_SILENCE = "silence"
        private const val REASON_MANUAL = "manual"
        private const val REASON_NO_SPEECH = "no_speech"
        private const val REASON_MAX_DURATION = "max_duration"
        private const val REASON_RECOGNIZER_FINAL = "recognizer_final"
        private const val REASON_CANCELLED = "cancelled"
        private const val REASON_DESTROYED = "destroyed"
        private const val REASON_ERROR = "error"
        private const val REASON_NOT_LISTENING = "not_listening"
    }
}

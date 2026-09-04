package com.honeypathkar.soundboxtts

import com.facebook.react.bridge.*

class SoundboxTtsModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "RNSoundboxTTS"

    @ReactMethod
    fun init(promise: Promise) {
        TtsEngine.init(reactContext) {
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun speak(text: String) {
        TtsEngine.speak(reactContext, text)
    }

    @ReactMethod
    fun speakPayment(params: ReadableMap) {
        val amountPaise = if (params.hasKey("amountPaise")) params.getDouble("amountPaise").toLong() else 0L
        val payerName = if (params.hasKey("payerName")) params.getString("payerName") else null
        val appName = if (params.hasKey("appName")) params.getString("appName") else null
        val language = if (params.hasKey("language")) params.getString("language") ?: "hi" else "hi"

        val sentence = SentenceBuilder.buildPaymentSentence(
            reactContext,
            amountPaise,
            payerName,
            appName,
            language
        )
        TtsEngine.setLanguage(language)
        TtsEngine.speak(reactContext, sentence)
    }

    @ReactMethod
    fun previewSentence(params: ReadableMap, promise: Promise) {
        val amountPaise = if (params.hasKey("amountPaise")) params.getDouble("amountPaise").toLong() else 0L
        val payerName = if (params.hasKey("payerName")) params.getString("payerName") else null
        val appName = if (params.hasKey("appName")) params.getString("appName") else null
        val language = if (params.hasKey("language")) params.getString("language") ?: "hi" else "hi"

        val sentence = SentenceBuilder.buildPaymentSentence(
            reactContext,
            amountPaise,
            payerName,
            appName,
            language
        )
        promise.resolve(sentence)
    }

    @ReactMethod
    fun setLanguage(languageCode: String, promise: Promise) {
        val supported = TtsEngine.setLanguage(languageCode)
        promise.resolve(supported)
    }

    @ReactMethod
    fun isLanguageAvailable(languageCode: String, promise: Promise) {
        val available = TtsEngine.isLanguageAvailable(languageCode)
        promise.resolve(available)
    }

    @ReactMethod
    fun setSpeechRate(rate: Float) {
        TtsEngine.speechRate = rate
    }

    @ReactMethod
    fun setVolume(volume: Float) {
        TtsEngine.volume = volume
    }

    @ReactMethod
    fun stop() {
        TtsEngine.shutdown()
    }
}

import Foundation
import AVFoundation
import Speech
import React

@objc(RNSpeechToText)
class RNSpeechToText: RCTEventEmitter {

  // MARK: - Events

  private enum Event: String, CaseIterable {
    case ready            = "onSpeechReady"
    case start            = "onSpeechStart"
    case partialResults   = "onSpeechPartialResults"
    case results          = "onSpeechResults"
    case silence          = "onSpeechSilence"
    case end              = "onSpeechEnd"
    case error            = "onSpeechError"
    case volumeChanged    = "onSpeechVolumeChanged"
  }

  // MARK: - Audio / recognition state

  private var audioEngine: AVAudioEngine?
  private var recognizer: SFSpeechRecognizer?
  private var request: SFSpeechAudioBufferRecognitionRequest?
  private var task: SFSpeechRecognitionTask?

  private var silenceTimer: DispatchSourceTimer?
  private var finalizeTimer: DispatchSourceTimer?

  private var hasListeners = false
  private var isListening = false
  private var isStopping = false
  private var didEmitFinal = false

  private var stopResolve: RCTPromiseResolveBlock?

  // MARK: - Configuration (per session)

  private var silenceTimeoutMs: Double = 2500
  private var noSpeechTimeoutMs: Double = 0
  private var maxDurationMs: Double = 0
  private var silenceThresholdDb: Float = -35.0
  private var detectionMode: String = "transcript"   // transcript | audio | hybrid
  private var interimResults = true
  private var continuousMode = false
  private var volumeUpdatesEnabled = true
  private var volumeIntervalMs: Double = 100

  // MARK: - Runtime state

  private var listeningStartedAt: TimeInterval = 0
  private var lastVoiceActivityAt: TimeInterval = 0
  private var lastVolumeEmitAt: TimeInterval = 0
  private var hasDetectedSpeech = false
  private var lastTranscript = ""
  private var pendingStopReason = "manual"

  private let stateLock = NSLock()

  // MARK: - RCTEventEmitter plumbing

  override func supportedEvents() -> [String]! {
    return Event.allCases.map { $0.rawValue }
  }

  override func startObserving() { setHasListeners(true) }

  override func stopObserving() { setHasListeners(false) }

  private func setHasListeners(_ value: Bool) {
    stateLock.lock()
    hasListeners = value
    stateLock.unlock()
  }

  private func emit(_ event: Event, _ body: [String: Any]) {
    stateLock.lock()
    let listening = hasListeners
    stateLock.unlock()
    guard listening else { return }
    sendEvent(withName: event.rawValue, body: body)
  }

  /// The module has no `methodQueue`, so exported methods arrive on React
  /// Native's per-module background queue. Everything that touches AVAudioEngine,
  /// the recognition task or the timers is funnelled onto the main queue.
  private func onMain(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
      block()
    } else {
      DispatchQueue.main.async(execute: block)
    }
  }

  // MARK: - Permissions

  @objc(requestPermissions:rejecter:)
  func requestPermissions(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
    SFSpeechRecognizer.requestAuthorization { speechStatus in
      self.requestMicrophoneAccess { micGranted in
        DispatchQueue.main.async {
          let speech = self.string(for: speechStatus)
          resolve([
            "speech": speech,
            "microphone": micGranted ? "granted" : "denied",
            "granted": speechStatus == .authorized && micGranted
          ])
        }
      }
    }
  }

  @objc(getPermissionStatus:rejecter:)
  func getPermissionStatus(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
    let speechStatus = SFSpeechRecognizer.authorizationStatus()
    let mic = currentMicrophoneStatus()
    resolve([
      "speech": string(for: speechStatus),
      "microphone": mic,
      "granted": speechStatus == .authorized && mic == "granted"
    ])
  }

  private func requestMicrophoneAccess(_ completion: @escaping (Bool) -> Void) {
    if #available(iOS 17.0, *) {
      AVAudioApplication.requestRecordPermission(completionHandler: completion)
    } else {
      AVAudioSession.sharedInstance().requestRecordPermission(completion)
    }
  }

  private func currentMicrophoneStatus() -> String {
    if #available(iOS 17.0, *) {
      switch AVAudioApplication.shared.recordPermission {
      case .granted: return "granted"
      case .denied: return "denied"
      case .undetermined: return "undetermined"
      @unknown default: return "undetermined"
      }
    } else {
      switch AVAudioSession.sharedInstance().recordPermission {
      case .granted: return "granted"
      case .denied: return "denied"
      case .undetermined: return "undetermined"
      @unknown default: return "undetermined"
      }
    }
  }

  private func string(for status: SFSpeechRecognizerAuthorizationStatus) -> String {
    switch status {
    case .authorized: return "granted"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "undetermined"
    @unknown default: return "undetermined"
    }
  }

  // MARK: - Capability discovery

  @objc(isAvailable:rejecter:)
  func isAvailable(_ resolve: @escaping RCTPromiseResolveBlock,
                   rejecter reject: @escaping RCTPromiseRejectBlock) {
    let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer()
    resolve(recognizer?.isAvailable ?? false)
  }

  @objc(isRecognitionAvailableForLocale:resolver:rejecter:)
  func isRecognitionAvailableForLocale(_ locale: String,
                                       resolver resolve: @escaping RCTPromiseResolveBlock,
                                       rejecter reject: @escaping RCTPromiseRejectBlock) {
    guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: normalize(locale))) else {
      resolve(false)
      return
    }
    resolve(recognizer.isAvailable)
  }

  @objc(supportsOnDeviceRecognition:resolver:rejecter:)
  func supportsOnDeviceRecognition(_ locale: String,
                                   resolver resolve: @escaping RCTPromiseResolveBlock,
                                   rejecter reject: @escaping RCTPromiseRejectBlock) {
    let id = locale.isEmpty ? Locale.current.identifier : normalize(locale)
    guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: id)) else {
      resolve(false)
      return
    }
    if #available(iOS 13.0, *) {
      resolve(recognizer.supportsOnDeviceRecognition)
    } else {
      resolve(false)
    }
  }

  @objc(getAvailableLocales:rejecter:)
  func getAvailableLocales(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
    let display = Locale.current
    let locales = SFSpeechRecognizer.supportedLocales()

    let mapped: [[String: Any]] = locales.map { locale in
      let identifier = bcp47(locale)
      let languageCode = locale.languageCode ?? identifier
      var entry: [String: Any] = [
        "identifier": identifier,
        "languageCode": languageCode,
        "name": display.localizedString(forIdentifier: locale.identifier)
          ?? locale.localizedString(forIdentifier: locale.identifier)
          ?? identifier,
        "nativeName": locale.localizedString(forIdentifier: locale.identifier) ?? identifier
      ]
      if let country = locale.regionCode {
        entry["countryCode"] = country
        entry["country"] = display.localizedString(forRegionCode: country) ?? country
      }
      return entry
    }
    .sorted { ($0["name"] as? String ?? "") < ($1["name"] as? String ?? "") }

    resolve(mapped)
  }

  @objc(getAvailableLanguages:rejecter:)
  func getAvailableLanguages(_ resolve: @escaping RCTPromiseResolveBlock,
                             rejecter reject: @escaping RCTPromiseRejectBlock) {
    let tags = SFSpeechRecognizer.supportedLocales().map { bcp47($0) }.sorted()
    resolve(tags)
  }

  private func bcp47(_ locale: Locale) -> String {
    return locale.identifier.replacingOccurrences(of: "_", with: "-")
  }

  private func normalize(_ locale: String) -> String {
    return locale.replacingOccurrences(of: "-", with: "_")
  }

  // MARK: - Start

  @objc(startListening:resolver:rejecter:)
  func startListening(_ options: NSDictionary,
                      resolver resolve: @escaping RCTPromiseResolveBlock,
                      rejecter reject: @escaping RCTPromiseRejectBlock) {
    onMain { self.performStart(options, resolve, reject) }
  }

  private func performStart(_ options: NSDictionary,
                            _ resolve: @escaping RCTPromiseResolveBlock,
                            _ reject: @escaping RCTPromiseRejectBlock) {

    if isListening {
      reject("already_listening", "Speech recognition is already running. Call stopListening() first.", nil)
      return
    }

    guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
      reject("permission_denied", "Speech recognition permission has not been granted. Call requestPermissions() first.", nil)
      return
    }

    guard currentMicrophoneStatus() == "granted" else {
      reject("permission_denied", "Microphone permission has not been granted. Call requestPermissions() first.", nil)
      return
    }

    applyOptions(options)

    let localeId = (options["locale"] as? String).flatMap { $0.isEmpty ? nil : normalize($0) }
      ?? Locale.current.identifier

    guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeId)) else {
      reject("locale_not_supported", "Locale '\(localeId)' is not supported by SFSpeechRecognizer on this device.", nil)
      return
    }

    guard recognizer.isAvailable else {
      reject("recognizer_unavailable", "The speech recognizer for '\(localeId)' is currently unavailable (offline, or the language pack is not downloaded).", nil)
      return
    }

    self.recognizer = recognizer

    let audioRequest = SFSpeechAudioBufferRecognitionRequest()
    audioRequest.shouldReportPartialResults = interimResults

    if #available(iOS 13.0, *) {
      let wantsOnDevice = options["requiresOnDeviceRecognition"] as? Bool ?? false
      if wantsOnDevice {
        guard recognizer.supportsOnDeviceRecognition else {
          reject("on_device_unavailable", "On-device recognition is not available for '\(localeId)'. Download the language for offline use in iOS Settings, or set requiresOnDeviceRecognition to false.", nil)
          return
        }
        audioRequest.requiresOnDeviceRecognition = true
      }
    }

    if #available(iOS 16.0, *) {
      audioRequest.addsPunctuation = options["addsPunctuation"] as? Bool ?? true
    }

    if let hint = options["taskHint"] as? String {
      switch hint {
      case "dictation": audioRequest.taskHint = .dictation
      case "search": audioRequest.taskHint = .search
      case "confirmation": audioRequest.taskHint = .confirmation
      default: audioRequest.taskHint = .unspecified
      }
    }

    if let phrases = options["contextualStrings"] as? [String], !phrases.isEmpty {
      audioRequest.contextualStrings = phrases
    }

    // Audio session
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
      try session.setActive(true, options: .notifyOthersOnDeactivation)
    } catch {
      teardownAudio()
      reject("audio_session_error", "Could not configure the audio session: \(error.localizedDescription)", error)
      return
    }

    let engine = AVAudioEngine()
    self.audioEngine = engine
    let inputNode = engine.inputNode
    let format = inputNode.outputFormat(forBus: 0)

    guard format.sampleRate > 0, format.channelCount > 0 else {
      teardownAudio()
      reject("audio_input_error", "No usable audio input format is available on this device.", nil)
      return
    }

    inputNode.removeTap(onBus: 0)
    inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      guard let self = self else { return }
      self.request?.append(buffer)
      self.processAudioLevel(buffer)
    }

    engine.prepare()
    do {
      try engine.start()
    } catch {
      inputNode.removeTap(onBus: 0)
      teardownAudio()
      reject("audio_engine_error", "Could not start the audio engine: \(error.localizedDescription)", error)
      return
    }

    self.request = audioRequest

    let now = CACurrentMediaTime()
    listeningStartedAt = now
    lastVoiceActivityAt = now
    lastVolumeEmitAt = 0
    hasDetectedSpeech = false
    lastTranscript = ""
    didEmitFinal = false
    isStopping = false
    isListening = true
    pendingStopReason = "manual"

    task = recognizer.recognitionTask(with: audioRequest) { [weak self] result, error in
      guard let self = self else { return }
      DispatchQueue.main.async {
        self.handleRecognition(result: result, error: error)
      }
    }

    startSilenceTimer()

    var usingOnDevice = false
    if #available(iOS 13.0, *) {
      usingOnDevice = audioRequest.requiresOnDeviceRecognition
    }

    emit(.ready, [
      "locale": bcp47(Locale(identifier: localeId)),
      "silenceTimeoutMs": silenceTimeoutMs,
      "onDevice": usingOnDevice
    ])

    resolve(true)
  }

  private func applyOptions(_ options: NSDictionary) {
    silenceTimeoutMs = (options["silenceTimeoutMs"] as? NSNumber)?.doubleValue ?? 2500
    noSpeechTimeoutMs = (options["noSpeechTimeoutMs"] as? NSNumber)?.doubleValue ?? 0
    maxDurationMs = (options["maxDurationMs"] as? NSNumber)?.doubleValue ?? 0
    silenceThresholdDb = (options["silenceThresholdDb"] as? NSNumber)?.floatValue ?? -35.0
    detectionMode = options["silenceDetectionMode"] as? String ?? "transcript"
    interimResults = options["interimResults"] as? Bool ?? true
    continuousMode = options["continuous"] as? Bool ?? false
    volumeUpdatesEnabled = options["volumeUpdates"] as? Bool ?? true
    volumeIntervalMs = (options["volumeIntervalMs"] as? NSNumber)?.doubleValue ?? 100
  }

  // MARK: - Recognition callback

  private func handleRecognition(result: SFSpeechRecognitionResult?, error: Error?) {
    guard isListening else { return }

    if let result = result {
      let transcript = result.bestTranscription.formattedString
      if !transcript.isEmpty && transcript != lastTranscript {
        lastTranscript = transcript

        if !hasDetectedSpeech {
          hasDetectedSpeech = true
          emit(.start, ["timestamp": Date().timeIntervalSince1970 * 1000])
        }

        if detectionMode != "audio" {
          lastVoiceActivityAt = CACurrentMediaTime()
        }

        if interimResults && !result.isFinal {
          emit(.partialResults, [
            "transcript": transcript,
            "isFinal": false,
            "segments": result.bestTranscription.segments.map { segment in
              return [
                "substring": segment.substring,
                "confidence": segment.confidence,
                "timestamp": segment.timestamp,
                "duration": segment.duration
              ] as [String: Any]
            }
          ])
        }
      }

      if result.isFinal {
        emitFinal(transcript: transcript)
        completeStop(reason: isStopping ? pendingStopReason : "recognizer_final")
        return
      }
    }

    if let error = error as NSError? {
      // 301 = task cancelled, 216 = task finished normally after endAudio.
      let benign = error.code == 301 || error.code == 216
      if isStopping || benign {
        completeStop(reason: isStopping ? pendingStopReason : "cancelled")
        return
      }
      emit(.error, [
        "code": mapErrorCode(error),
        "message": error.localizedDescription,
        "nativeCode": error.code
      ])
      completeStop(reason: "error")
    }
  }

  private func mapErrorCode(_ error: NSError) -> String {
    switch error.code {
    case 203: return "no_match"
    case 1110: return "no_speech_detected"
    case 1700: return "not_authorized"
    case 301: return "cancelled"
    default: return "recognition_error"
    }
  }

  // MARK: - Audio level / VAD

  private func processAudioLevel(_ buffer: AVAudioPCMBuffer) {
    guard let channelData = buffer.floatChannelData?[0] else { return }
    let frameLength = Int(buffer.frameLength)
    guard frameLength > 0 else { return }

    var sum: Float = 0
    for i in 0..<frameLength {
      let sample = channelData[i]
      sum += sample * sample
    }

    let rms = sqrt(sum / Float(frameLength))
    let db = rms > 0 ? 20 * log10(rms) : -160.0
    let normalized = max(0.0, min(1.0, (db + 60.0) / 60.0))

    let now = CACurrentMediaTime()

    if db > silenceThresholdDb && detectionMode != "transcript" {
      stateLock.lock()
      lastVoiceActivityAt = now
      stateLock.unlock()
    }

    guard volumeUpdatesEnabled else { return }
    guard (now - lastVolumeEmitAt) * 1000 >= volumeIntervalMs else { return }
    lastVolumeEmitAt = now

    DispatchQueue.main.async { [weak self] in
      self?.emit(.volumeChanged, ["value": normalized, "db": db])
    }
  }

  // MARK: - Silence timer

  private func startSilenceTimer() {
    silenceTimer?.cancel()
    let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
    timer.schedule(deadline: .now() + .milliseconds(100), repeating: .milliseconds(100))
    timer.setEventHandler { [weak self] in self?.tickSilence() }
    silenceTimer = timer
    timer.resume()
  }

  private func tickSilence() {
    guard isListening, !isStopping else { return }

    let now = CACurrentMediaTime()

    if maxDurationMs > 0 && (now - listeningStartedAt) * 1000 >= maxDurationMs {
      beginStop(reason: "max_duration")
      return
    }

    if !hasDetectedSpeech {
      if noSpeechTimeoutMs > 0 && (now - listeningStartedAt) * 1000 >= noSpeechTimeoutMs {
        emit(.error, ["code": "no_speech_detected", "message": "No speech was detected before the timeout elapsed."])
        beginStop(reason: "no_speech")
      }
      return
    }

    stateLock.lock()
    let idleMs = (now - lastVoiceActivityAt) * 1000
    stateLock.unlock()

    guard idleMs >= silenceTimeoutMs else { return }

    emit(.silence, ["durationMs": idleMs, "transcript": lastTranscript])

    if continuousMode {
      // Report the pause but keep the microphone open.
      stateLock.lock()
      lastVoiceActivityAt = now
      stateLock.unlock()
    } else {
      beginStop(reason: "silence")
    }
  }

  // MARK: - Stop / cancel

  @objc(stopListening:rejecter:)
  func stopListening(_ resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
    onMain { self.performStop(resolve) }
  }

  private func performStop(_ resolve: @escaping RCTPromiseResolveBlock) {
    guard isListening else {
      resolve(["transcript": lastTranscript, "reason": "not_listening"])
      return
    }
    stopResolve = resolve
    beginStop(reason: "manual")
  }

  @objc(cancel:rejecter:)
  func cancel(_ resolve: @escaping RCTPromiseResolveBlock,
              rejecter reject: @escaping RCTPromiseRejectBlock) {
    onMain { self.performCancel(resolve) }
  }

  private func performCancel(_ resolve: @escaping RCTPromiseResolveBlock) {
    guard isListening else {
      resolve(true)
      return
    }
    isStopping = true
    pendingStopReason = "cancelled"
    didEmitFinal = true          // suppress result emission on an explicit cancel
    task?.cancel()
    completeStop(reason: "cancelled")
    resolve(true)
  }

  @objc(destroy:rejecter:)
  func destroy(_ resolve: @escaping RCTPromiseResolveBlock,
               rejecter reject: @escaping RCTPromiseRejectBlock) {
    onMain { self.performDestroy(resolve) }
  }

  private func performDestroy(_ resolve: @escaping RCTPromiseResolveBlock) {
    if isListening {
      isStopping = true
      didEmitFinal = true
      task?.cancel()
      completeStop(reason: "destroyed")
    }
    lastTranscript = ""
    resolve(true)
  }

  /// Closes the microphone and waits for the recognizer to flush its final transcription.
  private func beginStop(reason: String) {
    guard isListening, !isStopping else { return }
    isStopping = true
    pendingStopReason = reason

    silenceTimer?.cancel()
    silenceTimer = nil

    audioEngine?.inputNode.removeTap(onBus: 0)
    audioEngine?.stop()
    request?.endAudio()

    // If the recognizer never returns a final result, finish anyway.
    finalizeTimer?.cancel()
    let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
    timer.schedule(deadline: .now() + .milliseconds(1500))
    timer.setEventHandler { [weak self] in
      guard let self = self else { return }
      self.emitFinal(transcript: self.lastTranscript)
      self.completeStop(reason: reason)
    }
    finalizeTimer = timer
    timer.resume()
  }

  private func emitFinal(transcript: String) {
    guard !didEmitFinal else { return }
    didEmitFinal = true
    emit(.results, [
      "transcript": transcript,
      "isFinal": true
    ])
  }

  private func completeStop(reason: String) {
    guard isListening else { return }

    silenceTimer?.cancel()
    silenceTimer = nil
    finalizeTimer?.cancel()
    finalizeTimer = nil

    let transcript = lastTranscript
    teardownAudio()

    isListening = false
    isStopping = false
    hasDetectedSpeech = false

    emit(.end, ["reason": reason, "transcript": transcript])

    if let resolve = stopResolve {
      stopResolve = nil
      resolve(["transcript": transcript, "reason": reason])
    }
  }

  private func teardownAudio() {
    if let engine = audioEngine {
      engine.inputNode.removeTap(onBus: 0)
      if engine.isRunning { engine.stop() }
    }
    audioEngine = nil
    task = nil
    request = nil
    recognizer = nil

    do {
      try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    } catch {
      // Deactivation can fail if another session took over; not fatal.
    }
  }

  deinit {
    silenceTimer?.cancel()
    finalizeTimer?.cancel()
  }
}

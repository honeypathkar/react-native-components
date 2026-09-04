/**
 * How the native silence timer decides that the user is still talking.
 *
 * - `transcript` (default) — the timer only resets when the recognizer emits new
 *   words. Immune to background noise, which is what you want in the real world.
 * - `audio` — the timer only resets while the input level stays above
 *   `silenceThresholdDb`. Most responsive, but a noisy room can keep it open.
 * - `hybrid` — either signal resets the timer. Safest against cutting off a slow
 *   speaker, at the cost of stopping later in noisy environments.
 */
export type SilenceDetectionMode = 'transcript' | 'audio' | 'hybrid';

export type SpeechTaskHint = 'unspecified' | 'dictation' | 'search' | 'confirmation';

export type PermissionState = 'granted' | 'denied' | 'restricted' | 'undetermined';

/** Why the native side stopped listening. */
export type SpeechEndReason =
  | 'silence'
  | 'manual'
  | 'no_speech'
  | 'max_duration'
  | 'recognizer_final'
  | 'cancelled'
  | 'destroyed'
  | 'error'
  | 'not_listening';

export interface StartListeningOptions {
  /** BCP-47 tag, e.g. `'en-US'`, `'hi-IN'`, `'gu-IN'`. Defaults to the device locale. */
  locale?: string;
  /** Pause length that triggers auto-stop, in milliseconds. Default `2500`. */
  silenceTimeoutMs?: number;
  /** Stream interim transcripts as the user speaks. Default `true`. */
  interimResults?: boolean;
  /**
   * When `true`, a detected pause fires `onSpeechSilence` but the microphone
   * stays open until you call `stopListening()`. Default `false` (auto-stop).
   */
  continuous?: boolean;
  /** Force offline recognition. Rejects if the locale has no on-device model. Default `false`. */
  requiresOnDeviceRecognition?: boolean;
  /** How the silence timer is driven. Default `'transcript'`. */
  silenceDetectionMode?: SilenceDetectionMode;
  /** iOS only. Input level below which audio counts as silence, in dBFS. Default `-35`. */
  silenceThresholdDb?: number;
  /**
   * Android only. RMS level above which audio counts as voice. Android reports a
   * different scale from iOS (roughly `-2`..`10`), so it has its own knob.
   * Default `2`. Only consulted when `silenceDetectionMode` is `audio` or `hybrid`.
   */
  androidSilenceThresholdRms?: number;
  /** Give up if the user never speaks. `0` disables it. Default `0`. */
  noSpeechTimeoutMs?: number;
  /** Hard cap on a single session. `0` disables it. Default `0`. */
  maxDurationMs?: number;
  /** iOS 16+ only. Automatic punctuation. Default `true`. */
  addsPunctuation?: boolean;
  /** iOS only. Tunes the recognizer for the kind of utterance you expect. */
  taskHint?: SpeechTaskHint;
  /** Uncommon words/names to bias recognition toward. Android needs API 33+. */
  contextualStrings?: string[];
  /** Emit `onSpeechVolumeChanged`. Default `true`. */
  volumeUpdates?: boolean;
  /** Throttle for volume events, in milliseconds. Default `100`. */
  volumeIntervalMs?: number;
}

export interface LocaleInfo {
  /** BCP-47 tag, e.g. `'en-US'`. */
  identifier: string;
  /** Language subtag, e.g. `'en'`. */
  languageCode: string;
  /** Display name in the device's language. */
  name: string;
  /** Display name in the locale's own language. */
  nativeName: string;
  countryCode?: string;
  country?: string;
}

export interface PermissionStatus {
  /** Android has no separate speech permission, so it mirrors `microphone` there. */
  speech: PermissionState;
  microphone: PermissionState;
  /** `true` only when both speech recognition and the microphone are granted. */
  granted: boolean;
}

export interface TranscriptSegment {
  substring: string;
  confidence: number;
  timestamp: number;
  duration: number;
}

export interface SpeechReadyEvent {
  locale: string;
  silenceTimeoutMs: number;
  onDevice: boolean;
}

export interface SpeechStartEvent {
  timestamp: number;
}

export interface SpeechPartialResultsEvent {
  transcript: string;
  isFinal: false;
  /** Per-word timing and confidence. iOS only; always empty on Android. */
  segments: TranscriptSegment[];
}

export interface SpeechResultsEvent {
  transcript: string;
  isFinal: true;
}

export interface SpeechSilenceEvent {
  /** How long the pause has lasted, in milliseconds. */
  durationMs: number;
  transcript: string;
}

export interface SpeechEndEvent {
  reason: SpeechEndReason;
  transcript: string;
}

export interface SpeechErrorEvent {
  code: string;
  message: string;
  nativeCode?: number;
}

export interface SpeechVolumeEvent {
  /** Normalized level in `0..1`, suitable for driving a meter. Comparable across platforms. */
  value: number;
  /** Raw level: dBFS on iOS, Android's `onRmsChanged` scale on Android. */
  db: number;
}

export interface StopListeningResult {
  transcript: string;
  reason: SpeechEndReason;
}

export interface SpeechEventMap {
  onSpeechReady: SpeechReadyEvent;
  onSpeechStart: SpeechStartEvent;
  onSpeechPartialResults: SpeechPartialResultsEvent;
  onSpeechResults: SpeechResultsEvent;
  onSpeechSilence: SpeechSilenceEvent;
  onSpeechEnd: SpeechEndEvent;
  onSpeechError: SpeechErrorEvent;
  onSpeechVolumeChanged: SpeechVolumeEvent;
}

export type SpeechEventName = keyof SpeechEventMap;

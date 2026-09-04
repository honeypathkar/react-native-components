import { Platform } from 'react-native';
import type { EmitterSubscription } from 'react-native';
import NativeSpeechToText, {
  isSupportedPlatform,
  speechEmitter,
} from './NativeSpeechToText';
import type {
  LocaleInfo,
  PermissionStatus,
  SpeechEventMap,
  SpeechEventName,
  StartListeningOptions,
  StopListeningResult,
} from './types';

export * from './types';
export { useSpeechToText } from './useSpeechToText';
export type { UseSpeechToTextOptions, UseSpeechToTextResult } from './useSpeechToText';

const UNSUPPORTED_PERMISSIONS: PermissionStatus = {
  speech: 'denied',
  microphone: 'denied',
  granted: false,
};

function assertSupported(method: string): void {
  if (!isSupportedPlatform) {
    throw new Error(
      `SpeechToText.${method}() is only available on iOS and Android. ` +
        `Current platform: ${Platform.OS}.`
    );
  }
}

export const SpeechToText = {
  /** `true` when this build actually has a native implementation (iOS and Android). */
  isSupported: isSupportedPlatform,

  /**
   * Prompts for speech recognition and microphone access. Both are required
   * before `startListening()` will resolve.
   */
  async requestPermissions(): Promise<PermissionStatus> {
    if (!isSupportedPlatform) return UNSUPPORTED_PERMISSIONS;
    return NativeSpeechToText.requestPermissions();
  },

  /** Reads the current permission state without prompting. */
  async getPermissionStatus(): Promise<PermissionStatus> {
    if (!isSupportedPlatform) return UNSUPPORTED_PERMISSIONS;
    return NativeSpeechToText.getPermissionStatus();
  },

  /** Whether the recognizer for the device locale is usable right now. */
  async isAvailable(): Promise<boolean> {
    if (!isSupportedPlatform) return false;
    return NativeSpeechToText.isAvailable();
  },

  /** Whether a specific BCP-47 locale can be recognized right now. */
  async isAvailableForLocale(locale: string): Promise<boolean> {
    if (!isSupportedPlatform) return false;
    return NativeSpeechToText.isRecognitionAvailableForLocale(locale);
  },

  /** Whether the locale has an offline model installed on this device. */
  async supportsOnDeviceRecognition(locale = ''): Promise<boolean> {
    if (!isSupportedPlatform) return false;
    return NativeSpeechToText.supportsOnDeviceRecognition(locale);
  },

  /** Every locale the device can transcribe, with display names. */
  async getAvailableLocales(): Promise<LocaleInfo[]> {
    if (!isSupportedPlatform) return [];
    return NativeSpeechToText.getAvailableLocales();
  },

  /** Every supported locale as plain BCP-47 tags. */
  async getAvailableLanguages(): Promise<string[]> {
    if (!isSupportedPlatform) return [];
    return NativeSpeechToText.getAvailableLanguages();
  },

  /**
   * Opens the microphone and starts streaming transcripts. Resolves once the
   * session is live; the transcript itself arrives through events (or through
   * the promise returned by `stopListening()`).
   */
  async startListening(options: StartListeningOptions = {}): Promise<boolean> {
    assertSupported('startListening');
    return NativeSpeechToText.startListening(options);
  },

  /**
   * Closes the microphone and resolves with the final transcript once the
   * recognizer has flushed everything it heard.
   */
  async stopListening(): Promise<StopListeningResult> {
    assertSupported('stopListening');
    return NativeSpeechToText.stopListening();
  },

  /** Stops immediately and discards the transcript. */
  async cancel(): Promise<boolean> {
    if (!isSupportedPlatform) return false;
    return NativeSpeechToText.cancel();
  },

  /** Tears down the session and releases the audio hardware. */
  async destroy(): Promise<boolean> {
    if (!isSupportedPlatform) return false;
    return NativeSpeechToText.destroy();
  },

  /** Subscribes to a native event. Keep the subscription and `.remove()` it on unmount. */
  on<E extends SpeechEventName>(
    event: E,
    listener: (payload: SpeechEventMap[E]) => void
  ): EmitterSubscription {
    if (!speechEmitter) {
      return { remove() {} } as EmitterSubscription;
    }
    return speechEmitter.addListener(event, listener);
  },

  /** Drops every listener for an event, or for all events when none is given. */
  removeAllListeners(event?: SpeechEventName): void {
    const emitter = speechEmitter;
    if (!emitter) return;
    if (event) {
      emitter.removeAllListeners(event);
      return;
    }
    (
      [
        'onSpeechReady',
        'onSpeechStart',
        'onSpeechPartialResults',
        'onSpeechResults',
        'onSpeechSilence',
        'onSpeechEnd',
        'onSpeechError',
        'onSpeechVolumeChanged',
      ] as SpeechEventName[]
    ).forEach((name) => emitter.removeAllListeners(name));
  },
};

export default SpeechToText;

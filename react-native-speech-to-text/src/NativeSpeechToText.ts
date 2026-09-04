import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import type {
  LocaleInfo,
  PermissionStatus,
  StartListeningOptions,
  StopListeningResult,
} from './types';

const LINKING_ERROR =
  `The package '@honeypathkar/react-native-speech-to-text' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({
    ios: "- You ran 'cd ios && pod install'\n",
    android:
      '- You rebuilt the Android app (autolinking picks the module up at build time)\n',
    default: '',
  }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

export interface SpeechToTextNativeModule {
  requestPermissions(): Promise<PermissionStatus>;
  getPermissionStatus(): Promise<PermissionStatus>;
  isAvailable(): Promise<boolean>;
  isRecognitionAvailableForLocale(locale: string): Promise<boolean>;
  supportsOnDeviceRecognition(locale: string): Promise<boolean>;
  getAvailableLocales(): Promise<LocaleInfo[]>;
  getAvailableLanguages(): Promise<string[]>;
  startListening(options: StartListeningOptions): Promise<boolean>;
  stopListening(): Promise<StopListeningResult>;
  cancel(): Promise<boolean>;
  destroy(): Promise<boolean>;
}

const NativeSpeechToText: SpeechToTextNativeModule =
  NativeModules.RNSpeechToText ??
  new Proxy({} as SpeechToTextNativeModule, {
    get() {
      throw new Error(LINKING_ERROR);
    },
  });

/**
 * `NativeEventEmitter` is only wired up when the native module actually
 * resolved, so importing the package on an unsupported platform stays harmless.
 */
export const speechEmitter = NativeModules.RNSpeechToText
  ? new NativeEventEmitter(NativeModules.RNSpeechToText)
  : null;

export const isSupportedPlatform =
  Platform.OS === 'ios' || Platform.OS === 'android';

export default NativeSpeechToText;

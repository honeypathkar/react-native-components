import { NativeModules, Platform } from 'react-native';
import { PaymentSpeakOptions, SupportedLanguage } from './types';

const LINKING_ERROR =
  `The package '@honeypathkar/react-native-soundbox-tts' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const RNSoundboxTTS = NativeModules.RNSoundboxTTS
  ? NativeModules.RNSoundboxTTS
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export const SoundboxTTS = {
  /**
   * Initializes the native TTS engine with USAGE_MEDIA audio focus.
   */
  async init(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNSoundboxTTS.init();
  },

  /**
   * Speaks raw text with transient audio ducking.
   */
  speak(text: string): void {
    if (Platform.OS === 'android') {
      RNSoundboxTTS.speak(text);
    }
  },

  /**
   * Announces a payment using Indian language sentence structures (SVO vs verb-final).
   */
  speakPayment(options: PaymentSpeakOptions): void {
    if (Platform.OS === 'android') {
      RNSoundboxTTS.speakPayment(options);
    }
  },

  /**
   * Generates what would be spoken without playing audio.
   */
  async previewSentence(options: PaymentSpeakOptions): Promise<string> {
    if (Platform.OS !== 'android') return '';
    return await RNSoundboxTTS.previewSentence(options);
  },

  /**
   * Sets the active language code (e.g. 'hi', 'en', 'mr', 'ta', 'te', 'bn', 'gu', 'kn').
   */
  async setLanguage(languageCode: SupportedLanguage | string): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNSoundboxTTS.setLanguage(languageCode);
  },

  /**
   * Checks if language voice package is downloaded & available on device.
   */
  async isLanguageAvailable(languageCode: SupportedLanguage | string): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNSoundboxTTS.isLanguageAvailable(languageCode);
  },

  /**
   * Configures speech rate (e.g. 1.0f).
   */
  setSpeechRate(rate: number): void {
    if (Platform.OS === 'android') {
      RNSoundboxTTS.setSpeechRate(rate);
    }
  },

  /**
   * Configures volume multiplier (0.0 to 1.0).
   */
  setVolume(volume: number): void {
    if (Platform.OS === 'android') {
      RNSoundboxTTS.setVolume(volume);
    }
  },

  /**
   * Stops TTS playback and releases audio focus.
   */
  stop(): void {
    if (Platform.OS === 'android') {
      RNSoundboxTTS.stop();
    }
  },
};

export * from './types';
export default SoundboxTTS;

import { NativeModules, Platform, EmitterSubscription } from 'react-native';
import NotificationListener, { ParsedPayment, FilterConfig } from '@honeypathkar/react-native-notification-listener';
import SoundboxTTS, { SupportedLanguage } from '@honeypathkar/react-native-soundbox-tts';
import { SoundboxServiceOptions } from './types';

const LINKING_ERROR =
  `The package '@honeypathkar/react-native-soundbox-engine' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const RNSoundboxEngine = NativeModules.RNSoundboxEngine
  ? NativeModules.RNSoundboxEngine
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

let paymentSubscription: EmitterSubscription | null = null;

export const SoundboxEngine = {
  /**
   * Starts the persistent background Soundbox ForegroundService with watchdog.
   */
  async startService(options: SoundboxServiceOptions = {}): Promise<boolean> {
    if (Platform.OS !== 'android') return false;

    // Start background Keep-Alive service
    await RNSoundboxEngine.startService(options);

    // Initialize TTS
    await SoundboxTTS.init();
    if (options.language) {
      await SoundboxTTS.setLanguage(options.language as SupportedLanguage);
    }

    // If auto-announce payments is enabled, subscribe to detected payments
    if (options.autoAnnouncePayments !== false) {
      if (paymentSubscription) {
        paymentSubscription.remove();
      }
      paymentSubscription = NotificationListener.onPaymentDetected((payment: ParsedPayment) => {
        SoundboxTTS.speakPayment({
          amountPaise: payment.amountPaise,
          payerName: payment.payerName,
          appName: payment.sourcePackage,
          language: options.language || 'hi',
        });
      });
    }

    return true;
  },

  /**
   * Stops the background Soundbox ForegroundService.
   */
  async stopService(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    if (paymentSubscription) {
      paymentSubscription.remove();
      paymentSubscription = null;
    }
    return await RNSoundboxEngine.stopService();
  },

  /**
   * Checks whether the ForegroundService is currently running.
   */
  async isServiceRunning(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNSoundboxEngine.isServiceRunning();
  },

  /**
   * Checks if app is exempt from Android Battery Optimizations.
   */
  async isIgnoringBatteryOptimizations(): Promise<boolean> {
    if (Platform.OS !== 'android') return true;
    return await RNSoundboxEngine.isIgnoringBatteryOptimizations();
  },

  /**
   * Prompts system dialog requesting exemption from Battery Optimization.
   */
  requestIgnoreBatteryOptimizations(): void {
    if (Platform.OS === 'android') {
      RNSoundboxEngine.requestIgnoreBatteryOptimizations();
    }
  },

  /**
   * Helper to configure payment listener filter allowlists.
   */
  configureFilters(config: FilterConfig): void {
    NotificationListener.configureFilters(config);
  },
};

export * from './types';
export * from './hooks/useSoundboxStatus';
export default SoundboxEngine;

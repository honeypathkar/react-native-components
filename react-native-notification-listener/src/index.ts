import { NativeModules, NativeEventEmitter, Platform } from 'react-native';
import { FilterConfig, NotificationPayload, ParsedPayment } from './types';

const LINKING_ERROR =
  `The package '@honeypathkar/react-native-notification-listener' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const RNNotificationListener = NativeModules.RNNotificationListener
  ? NativeModules.RNNotificationListener
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

const eventEmitter = new NativeEventEmitter(RNNotificationListener);

export const NotificationListener = {
  /**
   * Checks if Android Notification Listener permission is granted.
   */
  async isPermissionGranted(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNNotificationListener.isPermissionGranted();
  },

  /**
   * Deep-links directly to Android Notification Access settings screen.
   */
  openPermissionSettings(): void {
    if (Platform.OS === 'android') {
      RNNotificationListener.openPermissionSettings();
    }
  },

  /**
   * Configures dynamic filter rules (package allowlists, chat suppression, etc.)
   */
  configureFilters(config: FilterConfig): void {
    if (Platform.OS === 'android') {
      RNNotificationListener.configureFilters(config);
    }
  },

  /**
   * Fetches persisted payment records from offline SQLite database.
   */
  async getPayments(limit = 40, offset = 0): Promise<ParsedPayment[]> {
    if (Platform.OS !== 'android') return [];
    return await RNNotificationListener.getPayments(limit, offset);
  },

  /**
   * Subscribes to raw incoming notification events.
   */
  onNotificationReceived(listener: (payload: NotificationPayload) => void) {
    return eventEmitter.addListener('onNotificationReceived', listener);
  },

  /**
   * Subscribes to parsed incoming payment events.
   */
  onPaymentDetected(listener: (payment: ParsedPayment) => void) {
    return eventEmitter.addListener('onPaymentDetected', listener);
  },
};

export * from './types';
export default NotificationListener;

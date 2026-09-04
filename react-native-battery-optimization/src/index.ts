import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package '@honeypathkar/react-native-battery-optimization' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const RNBatteryOptimization = NativeModules.RNBatteryOptimization
  ? NativeModules.RNBatteryOptimization
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export const BatteryOptimization = {
  /**
   * Checks if app is currently exempt from Android Battery Optimization (Doze Mode).
   */
  async isIgnoringBatteryOptimizations(): Promise<boolean> {
    if (Platform.OS !== 'android') return true;
    return await RNBatteryOptimization.isIgnoringBatteryOptimizations();
  },

  /**
   * Directly triggers the system modal to request battery exemption.
   */
  async requestIgnoreBatteryOptimizations(): Promise<boolean> {
    if (Platform.OS !== 'android') return true;
    return await RNBatteryOptimization.requestIgnoreBatteryOptimizations();
  },

  /**
   * Opens the system Battery Optimization management settings screen.
   */
  openBatteryOptimizationSettings(): void {
    if (Platform.OS === 'android') {
      RNBatteryOptimization.openBatteryOptimizationSettings();
    }
  },

  /**
   * Opens manufacturer-specific AutoStart settings (Xiaomi/MIUI, Oppo/ColorOS, Vivo, Huawei, Samsung).
   */
  async openAutoStartSettings(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    return await RNBatteryOptimization.openAutoStartSettings();
  },

  /**
   * Opens the app's system details settings screen.
   */
  openAppDetailsSettings(): void {
    if (Platform.OS === 'android') {
      RNBatteryOptimization.openAppDetailsSettings();
    }
  },
};

export * from './types';
export * from './hooks/useBatteryOptimization';
export default BatteryOptimization;

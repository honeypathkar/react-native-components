import { useState, useEffect, useCallback } from 'react';
import { AppState, AppStateStatus } from 'react-native';
import SoundboxEngine from '../index';
import NotificationListener from '@honeypathkar/react-native-notification-listener';
import { SoundboxStatusState } from '../types';

export function useSoundboxStatus(): SoundboxStatusState {
  const [isServiceRunning, setIsServiceRunning] = useState<boolean>(false);
  const [isBatteryOptimizedIgnored, setIsBatteryOptimizedIgnored] = useState<boolean>(false);
  const [isNotificationAccessGranted, setIsNotificationAccessGranted] = useState<boolean>(false);

  const refresh = useCallback(async () => {
    try {
      const running = await SoundboxEngine.isServiceRunning();
      const battery = await SoundboxEngine.isIgnoringBatteryOptimizations();
      const access = await NotificationListener.isPermissionGranted();

      setIsServiceRunning(running);
      setIsBatteryOptimizedIgnored(battery);
      setIsNotificationAccessGranted(access);
    } catch {
      // Ignored
    }
  }, []);

  useEffect(() => {
    refresh();

    const handleAppStateChange = (state: AppStateStatus) => {
      if (state === 'active') {
        refresh();
      }
    };

    const sub = AppState.addEventListener('change', handleAppStateChange);
    return () => {
      sub.remove();
    };
  }, [refresh]);

  return {
    isServiceRunning,
    isBatteryOptimizedIgnored,
    isNotificationAccessGranted,
    refresh,
  };
}

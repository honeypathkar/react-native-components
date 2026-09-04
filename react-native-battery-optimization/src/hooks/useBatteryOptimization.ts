import { useState, useEffect, useCallback } from 'react';
import { AppState, AppStateStatus } from 'react-native';
import BatteryOptimization from '../index';
import { BatteryOptimizationState } from '../types';

export function useBatteryOptimization(): BatteryOptimizationState {
  const [isIgnoring, setIsIgnoring] = useState<boolean>(true);
  const [loading, setLoading] = useState<boolean>(true);

  const refresh = useCallback(async () => {
    try {
      const ignoring = await BatteryOptimization.isIgnoringBatteryOptimizations();
      setIsIgnoring(ignoring);
    } catch {
      // Ignored
    } finally {
      setLoading(false);
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

  const requestExemption = useCallback(async () => {
    const result = await BatteryOptimization.requestIgnoreBatteryOptimizations();
    await refresh();
    return result;
  }, [refresh]);

  return {
    isIgnoring,
    loading,
    refresh,
    requestExemption,
    openSettings: BatteryOptimization.openBatteryOptimizationSettings,
    openAutoStart: BatteryOptimization.openAutoStartSettings,
  };
}

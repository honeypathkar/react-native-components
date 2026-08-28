import { useEffect, useState } from 'react';
import { PermissionsAndroid, Platform } from 'react-native';
import StepCounter from './index';

export interface UseStepCounterReturn {
  steps: number;
  rawSensorValue: number;
  isSupported: boolean;
  start: () => Promise<boolean>;
  stop: () => Promise<boolean>;
  resetToday: () => Promise<boolean>;
}

export function useStepCounter(): UseStepCounterReturn {
  const [steps, setSteps] = useState<number>(0);
  const [rawSensorValue, setRawSensorValue] = useState<number>(0);
  const [isSupported, setIsSupported] = useState<boolean>(false);

  useEffect(() => {
    let subscription: ReturnType<typeof StepCounter.addListener> = null;

    async function init() {
      const supported = await StepCounter.isSupported();
      setIsSupported(supported);

      if (supported) {
        // Request runtime ACTIVITY_RECOGNITION permission on Android Q (API 29+)
        if (Platform.OS === 'android' && Platform.Version >= 29) {
          const granted = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION,
            {
              title: 'Physical Activity Permission',
              message: 'App needs access to physical activity recognition to count your steps.',
              buttonPositive: 'OK',
            }
          );
          if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
            console.warn('ACTIVITY_RECOGNITION permission denied');
            return;
          }
        }

        const initialToday = await StepCounter.getTodaySteps();
        const initialRaw = await StepCounter.getCurrentSensorValue();
        setSteps(initialToday);
        setRawSensorValue(initialRaw);

        subscription = StepCounter.addListener((event) => {
          setSteps(event.today);
          setRawSensorValue(event.rawSensorValue);
        });

        await StepCounter.start();
      }
    }

    init();

    return () => {
      if (subscription) {
        subscription.remove();
      }
    };
  }, []);

  const start = async () => StepCounter.start();
  const stop = async () => StepCounter.stop();
  const resetToday = async () => {
    const success = await StepCounter.resetToday();
    if (success) {
      setSteps(0);
    }
    return success;
  };

  return {
    steps,
    rawSensorValue,
    isSupported,
    start,
    stop,
    resetToday,
  };
}

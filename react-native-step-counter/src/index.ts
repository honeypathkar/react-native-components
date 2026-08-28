import { NativeEventEmitter, NativeModules, EmitterSubscription, Platform } from 'react-native';
import NativeStepCounter from './NativeStepCounter';
import { StepCounterListener, StepUpdateEvent } from './types';

const isAndroid = Platform.OS === 'android';
const eventEmitter = isAndroid && NativeModules.StepCounter 
  ? new NativeEventEmitter(NativeModules.StepCounter) 
  : null;

class StepCounterManager {
  /**
   * Check if hardware step counter sensor is available.
   */
  async isSupported(): Promise<boolean> {
    if (!isAndroid || !NativeStepCounter) return false;
    return NativeStepCounter.isSupported();
  }

  /**
   * Start listening to step counter updates.
   */
  async start(): Promise<boolean> {
    if (!isAndroid || !NativeStepCounter) return false;
    return NativeStepCounter.start();
  }

  /**
   * Stop step counter updates.
   */
  async stop(): Promise<boolean> {
    if (!isAndroid || !NativeStepCounter) return false;
    return NativeStepCounter.stop();
  }

  /**
   * Get total step count for today (currentSensorValue - baseline).
   */
  async getTodaySteps(): Promise<number> {
    if (!isAndroid || !NativeStepCounter) return 0;
    return NativeStepCounter.getTodaySteps();
  }

  /**
   * Get raw cumulative step count from TYPE_STEP_COUNTER sensor.
   */
  async getCurrentSensorValue(): Promise<number> {
    if (!isAndroid || !NativeStepCounter) return 0;
    return NativeStepCounter.getCurrentSensorValue();
  }

  /**
   * Reset today's baseline manually.
   */
  async resetToday(): Promise<boolean> {
    if (!isAndroid || !NativeStepCounter) return false;
    return NativeStepCounter.resetToday();
  }

  /**
   * Register step update listener.
   */
  addListener(listener: StepCounterListener): EmitterSubscription | null {
    if (!eventEmitter) return null;
    return eventEmitter.addListener('StepCounterUpdate', (event: StepUpdateEvent) => {
      listener(event);
    });
  }
}

const StepCounter = new StepCounterManager();
export default StepCounter;
export * from './types';
export { useStepCounter } from './useStepCounter';

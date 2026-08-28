import { NativeModules } from 'react-native';

const { StepCounter } = NativeModules;

export interface NativeStepCounterInterface {
  isSupported(): Promise<boolean>;
  start(): Promise<boolean>;
  stop(): Promise<boolean>;
  getTodaySteps(): Promise<number>;
  getCurrentSensorValue(): Promise<number>;
  resetToday(): Promise<boolean>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default StepCounter as NativeStepCounterInterface;

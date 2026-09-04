export interface BatteryOptimizationState {
  isIgnoring: boolean;
  loading: boolean;
  refresh: () => Promise<void>;
  requestExemption: () => Promise<boolean>;
  openSettings: () => void;
  openAutoStart: () => Promise<boolean>;
}

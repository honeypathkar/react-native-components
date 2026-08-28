export interface StepUpdateEvent {
  today: number;
  rawSensorValue: number;
}

export type StepCounterListener = (event: StepUpdateEvent) => void;

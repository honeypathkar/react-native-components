export interface SoundboxServiceOptions {
  notificationTitle?: string;
  notificationMessage?: string;
  autoAnnouncePayments?: boolean;
  language?: string;
}

export interface SoundboxStatusState {
  isServiceRunning: boolean;
  isBatteryOptimizedIgnored: boolean;
  isNotificationAccessGranted: boolean;
  refresh: () => Promise<void>;
}

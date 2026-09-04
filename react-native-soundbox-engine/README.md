# @honeypathkar/react-native-soundbox-engine

Complete background Soundbox orchestrator for React Native. Combines `@honeypathkar/react-native-notification-listener` and `@honeypathkar/react-native-soundbox-tts` with background persistence.

- **`START_STICKY` ForegroundService**: Persistent foreground keep-alive service with specialUse / mediaPlayback attributes for Android 14+.
- **15-Minute Alarm Watchdog**: Self-healing service watchdog that re-arms and revives the service if stopped by OEM power managers.
- **Boot Revival**: `RECEIVE_BOOT_COMPLETED` receiver to automatically resume soundbox monitoring on device restart.
- **Battery Optimization Helper**: Direct check and deep-link prompt for battery exemption.
- **React Hooks**: Pre-built `useSoundboxStatus()` hook for real-time permission and service state tracking.

## Installation

```bash
npm install @honeypathkar/react-native-soundbox-engine @honeypathkar/react-native-notification-listener @honeypathkar/react-native-soundbox-tts
# or
yarn add @honeypathkar/react-native-soundbox-engine @honeypathkar/react-native-notification-listener @honeypathkar/react-native-soundbox-tts
```

## Quick Start

```typescript
import SoundboxEngine, { useSoundboxStatus } from '@honeypathkar/react-native-soundbox-engine';
import { View, Text, Button } from 'react-native';

export function SoundboxDashboard() {
  const { isServiceRunning, isNotificationAccessGranted, isBatteryOptimizedIgnored, refresh } = useSoundboxStatus();

  const handleStart = async () => {
    await SoundboxEngine.startService({
      notificationTitle: 'My Store Soundbox',
      notificationMessage: 'Listening for UPI & bank payments',
      autoAnnouncePayments: true,
      language: 'hi', // Hindi payment announcements
    });
    refresh();
  };

  const handleStop = async () => {
    await SoundboxEngine.stopService();
    refresh();
  };

  return (
    <View style={{ padding: 20 }}>
      <Text>Service Status: {isServiceRunning ? 'Active' : 'Stopped'}</Text>
      <Text>Notification Access: {isNotificationAccessGranted ? 'Granted' : 'Missing'}</Text>
      <Text>Battery Exemption: {isBatteryOptimizedIgnored ? 'Exempt' : 'Optimized'}</Text>

      <Button title="Start Soundbox" onPress={handleStart} />
      <Button title="Stop Soundbox" onPress={handleStop} />
    </View>
  );
}
```

## License
MIT

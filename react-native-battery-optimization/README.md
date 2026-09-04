# @honeypathkar/react-native-battery-optimization

Cross-OEM Android Battery Optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and manufacturer AutoStart settings helper for React Native background services, location tracking, soundbox listeners, and step counters.

## Features

- **Battery Optimization Exemption**: Check and request exemption from Android Doze mode directly without deep-linking merchant confusion.
- **Cross-OEM AutoStart Launchers**: Built-in intent dispatchers for aggressive manufacturer task killers:
  - Xiaomi / MIUI / HyperOS
  - Oppo / Realme / ColorOS
  - Vivo / iQOO / FuntouchOS
  - Huawei / HarmonyOS / EMUI
  - Samsung OneUI
- **React Hook**: `useBatteryOptimization()` for reactive permission state tracking.

## Installation

```bash
npm install @honeypathkar/react-native-battery-optimization
# or
yarn add @honeypathkar/react-native-battery-optimization
```

## Android Permissions

Add the permission to your `android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

## Usage

### 1. Using the React Hook

```typescript
import { useBatteryOptimization } from '@honeypathkar/react-native-battery-optimization';
import { View, Text, Button } from 'react-native';

export function BatteryStatusCard() {
  const { isIgnoring, loading, requestExemption, openAutoStart } = useBatteryOptimization();

  return (
    <View>
      <Text>Battery Exemption: {isIgnoring ? 'Active (Unrestricted)' : 'Optimized (Restricted)'}</Text>
      
      {!isIgnoring && (
        <Button title="Disable Battery Optimization" onPress={requestExemption} />
      )}

      <Button title="Open Manufacturer AutoStart Settings" onPress={openAutoStart} />
    </View>
  );
}
```

### 2. Direct Method Calls

```typescript
import BatteryOptimization from '@honeypathkar/react-native-battery-optimization';

// Check status
const isIgnoring = await BatteryOptimization.isIgnoringBatteryOptimizations();

// Prompt system exemption dialog
if (!isIgnoring) {
  await BatteryOptimization.requestIgnoreBatteryOptimizations();
}

// Open OEM autostart settings (Xiaomi, Oppo, Vivo, etc.)
await BatteryOptimization.openAutoStartSettings();
```

## License
MIT

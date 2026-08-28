# @honeypathkar/react-native-step-counter

A production-ready React Native library for Android to measure **Today's Step Count** using hardware `Sensor.TYPE_STEP_COUNTER` with automatic baseline recalibration, reboot recovery, midnight resets, and WorkManager background synchronization.

---

## 💡 How It Works (Architecture Overview)

Unlike accelerometer-based pedometers that drain battery by constantly calculating step algorithms, Android devices feature a low-power hardware sensor: `Sensor.TYPE_STEP_COUNTER`.

### Hardware Behavior & Challenges
- **Accumulated Total**: The hardware counter outputs a cumulative step count since the device last booted (e.g. `45,210`).
- **Device Boot Reset**: When the device restarts/reboots, the hardware sensor resets back to `0`.
- **No Native Daily Reset**: The hardware sensor **never resets at midnight**; it keeps incrementing continuously.

### How `@honeypathkar/react-native-step-counter` Solves This

```
┌─────────────────────────────────────────────────────────────┐
│ Hardware Sensor: TYPE_STEP_COUNTER (Raw: 10,500)            │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ StepStorage (Baseline recalibration)                        │
│ Baseline = 10,000 (Saved at midnight/first start)           │
│                                                             │
│ Today's Steps = Raw (10,500) - Baseline (10,000) = 500       │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌───────────────────────┐             ┌───────────────────────┐
│ Reboot Handler        │             │ Midnight Receiver     │
│ If Raw < Baseline     │             │ Listens to ACTION_    │
│ -> Baseline = Raw     │             │ DATE_CHANGED          │
│ Prevents negative UI  │             │ Sets new daily baseline│
└───────────────────────┘             └───────────────────────┘
```

1. **Daily Step Calculation**:
   Calculates today's steps using `todaySteps = rawSensorValue - baseline`.
2. **Reboot Recovery**:
   Detects phone restarts (`rawSensorValue < baseline`). Automatically updates baseline so steps don't break or reset improperly.
3. **Midnight Reset (`MidnightReceiver`)**:
   Listens for system broadcasts (`ACTION_DATE_CHANGED`, `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`) to store a new baseline at 00:00 AM automatically.
4. **WorkManager Sync (`StepSyncWorker`)**:
   Runs periodic background syncs (every 15 minutes) using WorkManager to register step counts even if the app UI is closed.

---

## 📦 Installation

```bash
# Using npm
npm install @honeypathkar/react-native-step-counter

# Using yarn
yarn add @honeypathkar/react-native-step-counter
```

---

## 🤖 Android Setup & Permissions

Add `ACTIVITY_RECOGNITION` permission in your `AndroidManifest.xml` (autolinking handles native module setup):

```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

For **Android 10+ (API level 29+)**, ask runtime permission in React Native:

```javascript
import { PermissionsAndroid, Platform } from 'react-native';

async function requestActivityPermission() {
  if (Platform.OS === 'android' && Platform.Version >= 29) {
    const granted = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
    );
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  }
  return true;
}
```

---

## 🚀 Usage

### Option 1: React Hook (Recommended)

```tsx
import React, { useEffect } from 'react';
import { View, Text, Button, StyleSheet } from 'react-native';
import { useStepCounter } from '@honeypathkar/react-native-step-counter';

export default function StepTracker() {
  const { steps, rawSensorValue, isSupported, resetToday } = useStepCounter();

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Step Tracker</Text>
      <Text>Supported: {isSupported ? 'Yes ✅' : 'No ❌'}</Text>
      <Text style={styles.steps}>Today's Steps: {steps}</Text>
      <Text style={styles.sub}>Raw Hardware Sensor: {rawSensorValue}</Text>

      <Button title="Reset Today's Baseline" onPress={resetToday} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { padding: 20, alignItems: 'center' },
  title: { fontSize: 20, fontWeight: 'bold' },
  steps: { fontSize: 32, color: '#4CAF50', marginVertical: 10 },
  sub: { fontSize: 12, color: '#888', marginBottom: 15 },
});
```

### Option 2: Imperative API & Event Listeners

```typescript
import StepCounter from '@honeypathkar/react-native-step-counter';

// 1. Check hardware support
const supported = await StepCounter.isSupported();

if (supported) {
  // 2. Start sensor listener
  await StepCounter.start();

  // 3. Get current today steps snapshot
  const todaySteps = await StepCounter.getTodaySteps();
  console.log('Today steps:', todaySteps);

  // 4. Listen for real-time updates
  const subscription = StepCounter.addListener((event) => {
    console.log("Today's steps updated:", event.today);
    console.log("Raw sensor value:", event.rawSensorValue);
  });

  // To unsubscribe:
  // subscription.remove();
}
```

---

## 🛠 API Reference

| Method | Return Type | Description |
| --- | --- | --- |
| `isSupported()` | `Promise<boolean>` | Returns whether `TYPE_STEP_COUNTER` is available on the device. |
| `start()` | `Promise<boolean>` | Starts listening to hardware step events. |
| `stop()` | `Promise<boolean>` | Unregisters step event listener. |
| `getTodaySteps()` | `Promise<number>` | Returns current calculated steps for today. |
| `getCurrentSensorValue()` | `Promise<number>` | Returns raw hardware step counter since boot. |
| `resetToday()` | `Promise<boolean>` | Resets baseline to current hardware raw counter value. |

---

## 📄 License
MIT © 2026

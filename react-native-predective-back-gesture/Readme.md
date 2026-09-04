# @honeypathkar/react-native-predictive-back-gesture

<div align="center">
  <img src="https://mintcdn.com/honeypathkar/2crkeqrZKInE-b4M/images/ezgif-154f2a3ceac3de29.gif?s=be7bda13989332cd5d3b93d1c5ced59f" width="280" alt="Predictive Back Gesture Demo" />
</div>

A lightweight React Native library providing Android 14+ Predictive Back gesture animations and edge drag-to-close card transitions.

## Features

- **Android 14+ (API 34+):** Live OS gesture progress. The card shrinks 13%, tilts dynamically, and reveals the underlying screen during mid-swipe.
- **Android < 14 / Back Button:** Plays a smooth slide-out card exit animation on back commit.
- **Two Animation Styles:** `card` (Material predictive back) or `slide` (full-width edge exit), selected per screen.
- **Cross-Platform Swipe Gesture:** Left-edge drag-to-close gesture on all platforms (iOS & Android).
- **Root Back-To-Home:** Root screen stands down so Android plays its native *back-to-home* animation (app shrinking over wallpaper).
- **Zero Config Autolinking:** Native Android module auto-linked seamlessly.

---

## Installation

```bash
npm install @honeypathkar/react-native-predictive-back-gesture
# or
yarn add @honeypathkar/react-native-predictive-back-gesture
```

### Peer Dependencies

Ensure `react-native-gesture-handler` and `react-native-reanimated` are installed in your project:

```bash
npm install react-native-gesture-handler react-native-reanimated
```

---

## Android Setup

### 1. Opt-in to Predictive Back in `AndroidManifest.xml`

In your `android/app/src/main/AndroidManifest.xml`, add `android:enableOnBackInvokedCallback="true"` to the `<application>` tag:

```xml
<application
  android:name=".MainApplication"
  android:enableOnBackInvokedCallback="true"
  ... >
```

---

## Usage

### 1. Wrap Root with `GestureHandlerRootView`

At the entry point of your app (`App.jsx` or `index.js`), wrap your root layout with `GestureHandlerRootView`:

```jsx
import React from 'react';
import { StyleSheet, View } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import SwipeableScreen, {
  setFallbackBackMode,
  BACK_MODE_SYSTEM,
  BACK_MODE_DEFAULT,
} from '@honeypathkar/react-native-predictive-back-gesture';

import HomeScreen from './screens/HomeScreen';
import DetailsScreen from './screens/DetailsScreen';

export default function App() {
  const [currentScreen, setCurrentScreen] = React.useState('home');

  // Synchronize back mode:
  // When at root -> BACK_MODE_SYSTEM (plays Android native back-to-home wallpaper animation)
  // When on sub-screen -> BACK_MODE_DEFAULT
  React.useEffect(() => {
    setFallbackBackMode(
      currentScreen === 'home' ? BACK_MODE_SYSTEM : BACK_MODE_DEFAULT,
    );
  }, [currentScreen]);

  return (
    <GestureHandlerRootView style={styles.flex}>
      <View style={styles.flex}>
        {/* Root screen stays mounted underneath */}
        <HomeScreen onOpenDetails={() => setCurrentScreen('details')} />

        {/* Sub-screen wrapped inside SwipeableScreen */}
        {currentScreen === 'details' ? (
          <View style={StyleSheet.absoluteFill}>
            <SwipeableScreen
              onGoBack={() => setCurrentScreen('home')}
              backgroundColor="#0A0A0F"
            >
              <DetailsScreen onBack={() => setCurrentScreen('home')} />
            </SwipeableScreen>
          </View>
        ) : null}
      </View>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
});
```

---

## Animation Types

`animation` selects how the screen moves while the gesture is in flight. Both
follow the finger; they differ in where the screen goes.

| Value | Motion | Feels like |
| --- | --- | --- |
| `card` *(default)* | Screen shrinks and drifts, staying on screen so the user can see what is behind it. Leaves only on commit. | Material 3 predictive back |
| `slide` | Screen tracks the finger across the full width and walks off the edge. | Stock iOS / most apps today |

```jsx
import SwipeableScreen, {
  ANIMATION_CARD,
  ANIMATION_SLIDE,
} from '@honeypathkar/react-native-predictive-back-gesture';

// Material predictive back — the default, nothing to pass
<SwipeableScreen>
  <YourScreen />
</SwipeableScreen>

// Full-width slide
<SwipeableScreen animation="slide">
  <YourScreen />
</SwipeableScreen>

// Or use the exported constants
<SwipeableScreen animation={ANIMATION_SLIDE}>
  <YourScreen />
</SwipeableScreen>
```

The default is `card`, so screens written before this prop existed keep their
current behaviour.

### Tuning the slide

`peekTravel` controls how far the outgoing screen travels, as a fraction of
screen width. It only applies to `slide`.

```jsx
<SwipeableScreen animation="slide" peekTravel={0.85}>
  <YourScreen />
</SwipeableScreen>
```

Lower values keep more of the outgoing screen visible during the gesture;
`1` walks it fully off the edge. Defaults to `0.6`.

---

## React Navigation Integration

If you use `@react-navigation/native`, synchronize `setFallbackBackMode` directly on `NavigationContainer`:

```jsx
import {
  setFallbackBackMode,
  BACK_MODE_DEFAULT,
  BACK_MODE_SYSTEM,
} from '@honeypathkar/react-native-predictive-back-gesture';

const navigationRef = useNavigationContainerRef();

const syncBackMode = React.useCallback(() => {
  setFallbackBackMode(
    navigationRef.isReady() && navigationRef.canGoBack()
      ? BACK_MODE_DEFAULT
      : BACK_MODE_SYSTEM,
  );
}, []);

<NavigationContainer
  ref={navigationRef}
  onReady={syncBackMode}
  onStateChange={syncBackMode}
>
  {/* Stack Navigator */}
</NavigationContainer>
```

Wrap individual stack screens or custom modals with `<SwipeableScreen>`:

```jsx
import SwipeableScreen from '@honeypathkar/react-native-predictive-back-gesture';

function ProfileModal() {
  return (
    <SwipeableScreen backgroundColor="#12121A">
      <ProfileContent />
    </SwipeableScreen>
  );
}
```

---

## Props Reference (`<SwipeableScreen />`)

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `children` | `ReactNode` | **Required** | Content inside the swipeable screen card. |
| `enabled` | `boolean` | `true` | Enables or disables predictive back gesture handling. |
| `animation` | `'card' \| 'slide'` | `'card'` | How the screen moves during the gesture. See [Animation Types](#animation-types). |
| `peekTravel` | `number` | `0.6` | Fraction of screen width the outgoing screen travels. `slide` only. |
| `backgroundColor` | `string` | `'#000000'` | Background color of the card container. |
| `onGoBack` | `function` | `undefined` | Optional callback invoked when the screen pops or is swiped back. |
| `onHaptic` | `function` | `undefined` | Optional haptic feedback callback when swipe commits. |
| `style` | `ViewStyle` | `undefined` | Additional styles for the animated screen container. |

---

## License

MIT © [honeypathkar](https://github.com/honeypathkar)

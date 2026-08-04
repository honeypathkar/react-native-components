# Android Predictive Back Gesture — Implementation Guide

<div align="center">
  <img src="https://mintcdn.com/honeypathkar/2crkeqrZKInE-b4M/images/ezgif-154f2a3ceac3de29.gif?s=be7bda13989332cd5d3b93d1c5ced59f" width="280" alt="Predictive Back Gesture Demo" />
</div>

## What This Feature Does

- **Android 14+ (API 34+):** Live gesture progress from the OS → card shrinks 13%, drifts right, reveals the screen underneath while the finger is mid-swipe.
- **Android < 14:** Only the commit event fires → timed slide-out fallback animation plays instead.
- **All platforms:** A drag-to-close gesture from the left edge of the screen does the same card animation.
- **Root screen:** App stands down completely so Android plays its native _back-to-home_ animation (app shrinks into a card over the wallpaper).
- Haptic feedback on dismiss, proper gesture cancellation, and `beforeRemove` guard support.

---

## Architecture Overview

```
Android OS
  └── OnBackPressedDispatcher
        └── PredictiveBackModule.kt   (native, intercepts back events)
              └── NativeEventEmitter
                    └── PredictiveBack.js  (JS bridge, ownership model)
                          └── SwipeableScreen.jsx  (consumes events → Reanimated)
                                └── AppNavigator.jsx  (sets fallback mode per nav state)
```

---

## Files Changed / Created

| File                                                   | Status   | Role                               |
| ------------------------------------------------------ | -------- | ---------------------------------- |
| `android/app/build.gradle`                             | Modified | Add `activity-ktx` dependency      |
| `android/app/src/main/AndroidManifest.xml`             | Modified | Opt-in to predictive back          |
| `android/app/src/main/java/…/MainApplication.kt`       | Modified | Register `PredictiveBackPackage`   |
| `android/app/src/main/java/…/PredictiveBackModule.kt`  | **New**  | Native Kotlin bridge               |
| `android/app/src/main/java/…/PredictiveBackPackage.kt` | **New**  | ReactPackage wrapper               |
| `src/native/PredictiveBack.js`                         | **New**  | JS ownership model + event routing |
| `src/components/SwipeableScreen.jsx`                   | Modified | Reanimated card animation          |
| `src/navigation/AppNavigator.jsx`                      | Modified | Sync fallback back mode            |

---

## Step-by-Step Implementation

### 1. `android/app/build.gradle` — Add the Dependency

```diff
 dependencies {
     // ... existing deps

+    // BackEventCompat / predictive-back callbacks on OnBackPressedDispatcher (1.8.0+)
+    implementation("androidx.activity:activity-ktx:1.9.3")
 }
```

---

### 2. `android/app/src/main/AndroidManifest.xml` — Opt In

Add **`android:enableOnBackInvokedCallback="true"`** to the `<application>` tag.
Without this the OS never delivers predictive back events to your app.

```diff
  <application
    android:allowBackup="false"
    android:theme="@style/AppTheme"
    android:usesCleartextTraffic="${usesCleartextTraffic}"
-   android:supportsRtl="true">
+   android:supportsRtl="true"
+   android:enableOnBackInvokedCallback="true">
```

> [!IMPORTANT]
> This flag is the single most common reason the feature silently does nothing. It must be present at the `<application>` level (not `<activity>`).

---

### 3. `PredictiveBackPackage.kt` — New File

Create at `android/app/src/main/java/com/<yourpackage>/PredictiveBackPackage.kt`

```kotlin
package com.<yourpackage>

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class PredictiveBackPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(PredictiveBackModule(reactContext))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }
}
```

---

### 4. `PredictiveBackModule.kt` — New File (Core Native Logic)

Create at `android/app/src/main/java/com/<yourpackage>/PredictiveBackModule.kt`

**Key design decisions:**

- Uses `OnBackPressedCallback` (via `androidx.activity:activity-ktx`) NOT `OnBackInvokedCallback`. This is intentional — it lets the `OnBackPressedDispatcher` stack work correctly with React Native's own back handler and react-native-screens.
- Re-adds the callback on every switch to `app` mode so it stays on top of the dispatcher stack (LIFO order).
- Finds and manages React Native's own internal `OnBackPressedCallback` via reflection to enable the _back-to-home_ OS animation (the `system` mode).

```kotlin
package com.<yourpackage>

import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class PredictiveBackModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            emit(EVENT_START, backEvent.toEventMap())
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            emit(EVENT_PROGRESS, backEvent.toEventMap())
        }
        override fun handleOnBackCancelled() {
            emit(EVENT_CANCEL, Arguments.createMap())
        }
        override fun handleOnBackPressed() {
            emit(EVENT_COMMIT, Arguments.createMap())
        }
    }

    private var isRegistered = false
    private var reactCallback: OnBackPressedCallback? = null
    private var reactCallbackHost: ComponentActivity? = null

    override fun getName(): String = NAME

    override fun getConstants(): MutableMap<String, Any> =
        hashMapOf(
            "progressAvailable" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )

    @ReactMethod
    fun setMode(mode: String) {
        UiThreadUtil.runOnUiThread {
            val activity = reactApplicationContext.currentActivity as? ComponentActivity
            when (mode) {
                MODE_APP -> {
                    if (activity != null) {
                        attachTo(activity)
                        setReactCallbackEnabled(activity, true)
                    }
                    backCallback.isEnabled = true
                }
                MODE_SYSTEM -> {
                    backCallback.isEnabled = false
                    if (activity != null) setReactCallbackEnabled(activity, false)
                }
                MODE_DEFAULT -> {
                    backCallback.isEnabled = false
                    if (activity != null) setReactCallbackEnabled(activity, true)
                }
                else -> Log.w(NAME, "Unknown back mode: $mode")
            }
        }
    }

    @ReactMethod fun addListener(eventName: String) = Unit
    @ReactMethod fun removeListeners(count: Int) = Unit

    override fun invalidate() {
        UiThreadUtil.runOnUiThread {
            backCallback.isEnabled = false
            if (isRegistered) { backCallback.remove(); isRegistered = false }
            reactCallback?.isEnabled = true
            reactCallback = null
            reactCallbackHost = null
        }
        super.invalidate()
    }

    private fun attachTo(activity: ComponentActivity) {
        if (isRegistered) backCallback.remove()
        activity.onBackPressedDispatcher.addCallback(backCallback)
        isRegistered = true
    }

    private fun setReactCallbackEnabled(activity: ComponentActivity, enabled: Boolean) {
        findReactCallback(activity)?.isEnabled = enabled
    }

    private fun findReactCallback(activity: ComponentActivity): OnBackPressedCallback? {
        if (reactCallbackHost === activity) return reactCallback
        var cls: Class<*>? = activity.javaClass
        while (cls != null && cls != ComponentActivity::class.java) {
            for (field in cls.declaredFields) {
                if (OnBackPressedCallback::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val found = field.get(activity) as? OnBackPressedCallback
                        if (found != null) {
                            reactCallback = found; reactCallbackHost = activity
                            return found
                        }
                    } catch (e: Exception) {
                        Log.w(NAME, "Could not read React Native's back callback", e)
                        return null
                    }
                }
            }
            cls = cls.superclass
        }
        Log.w(NAME, "React Native's back callback not found; system back mode is a no-op")
        return null
    }

    private fun emit(event: String, payload: WritableMap) {
        val context = reactApplicationContext
        if (!context.hasActiveReactInstance()) return
        context.emitDeviceEvent(event, payload)
    }

    private fun BackEventCompat.toEventMap(): WritableMap =
        Arguments.createMap().apply {
            putDouble("progress", progress.toDouble())
            putInt("swipeEdge", swipeEdge)        // 0 = left, 1 = right
            putDouble("touchX", touchX.toDouble())
            putDouble("touchY", touchY.toDouble())
        }

    companion object {
        const val NAME = "PredictiveBackModule"
        private const val MODE_APP     = "app"
        private const val MODE_DEFAULT = "default"
        private const val MODE_SYSTEM  = "system"
        private const val EVENT_START    = "predictiveBackStart"
        private const val EVENT_PROGRESS = "predictiveBackProgress"
        private const val EVENT_CANCEL   = "predictiveBackCancel"
        private const val EVENT_COMMIT   = "predictiveBackCommit"
    }
}
```

---

### 5. `MainApplication.kt` — Register the Package

```diff
+ add(PredictiveBackPackage())
```

---

### 6. `src/native/PredictiveBack.js` — New File (JS Bridge + Ownership Model)

Create at `src/native/PredictiveBack.js` (adapt path to your project).

```js
import { NativeEventEmitter, NativeModules, Platform } from "react-native";

const PredictiveBackModule =
  Platform.OS === "android" ? NativeModules.PredictiveBackModule : null;

/** True when the native back-event bridge is present (Android only). */
export const PREDICTIVE_BACK_SUPPORTED = PredictiveBackModule != null;

/**
 * True when the OS also reports live gesture progress (Android 14 / API 34+).
 * Below that only the commit arrives — callers should play a timed animation.
 */
export const PREDICTIVE_BACK_HAS_PROGRESS =
  PREDICTIVE_BACK_SUPPORTED && PredictiveBackModule.progressAvailable === true;

export const EDGE_LEFT = 0;
export const EDGE_RIGHT = 1;

/** Back is React Native's to handle (BackHandler + React Navigation). */
export const BACK_MODE_DEFAULT = "default";
/**
 * Nothing in the app claims back → Android plays its own back-to-home animation.
 * Set this at the root of the stack (nothing to go back to).
 */
export const BACK_MODE_SYSTEM = "system";

let owner = null;
let handlers = null;
let fallbackMode = BACK_MODE_DEFAULT;
let appliedMode = null;
let subscriptions = null;

const apply = () => {
  const mode = owner ? "app" : fallbackMode;
  if (mode === appliedMode) return;
  appliedMode = mode;
  PredictiveBackModule.setMode(mode);
};

const dispatch = (name, event) => {
  const handler = handlers && handlers[name];
  if (handler) handler(event);
};

const ensureSubscribed = () => {
  if (subscriptions || !PredictiveBackModule) return;
  const emitter = new NativeEventEmitter(PredictiveBackModule);
  subscriptions = [
    emitter.addListener("predictiveBackStart", (e) => dispatch("onStart", e)),
    emitter.addListener("predictiveBackProgress", (e) =>
      dispatch("onProgress", e),
    ),
    emitter.addListener("predictiveBackCancel", () => dispatch("onCancel")),
    emitter.addListener("predictiveBackCommit", () => dispatch("onCommit")),
  ];
};

/**
 * What happens when no screen has claimed back.
 * Call from navigator on state change.
 */
export const setFallbackBackMode = (mode) => {
  if (!PredictiveBackModule || fallbackMode === mode) return;
  fallbackMode = mode;
  apply();
};

/**
 * Route back events to `nextHandlers` and enable the native callback.
 * `token` is any stable object (e.g. useRef({}).current) that identifies the caller.
 */
export const acquirePredictiveBack = (token, nextHandlers) => {
  if (!PredictiveBackModule) return;
  ensureSubscribed();
  owner = token;
  handlers = nextHandlers;
  appliedMode = null; // force re-apply even if mode string unchanged
  apply();
};

/** Give back control, but only if `token` still holds it. */
export const releasePredictiveBack = (token) => {
  if (!PredictiveBackModule || owner !== token) return;
  owner = null;
  handlers = null;
  apply();
};
```

---

### 7. `src/components/SwipeableScreen.jsx` — Modified

This is the most complex piece. The component wraps any screen and provides both the drag-to-close gesture and the Android predictive-back animation. Full code Available Inside src folder for this file.

**Key constants / parameters (tune to your taste):**

```js
const ENTER_DURATION = 240; // ms — slide-in
const EXIT_DURATION = 280; // ms — slide-out
const SPRING = { damping: 20, stiffness: 200 };
const EASING = Easing.out(Easing.cubic);
const EDGE_WIDTH = 60; // px — hit-slop width for drag zone
const HEADER_INSET = 75; // px — how far up the hit-slop extends above the screen
const DRAG_RANGE = 200; // px — full-drag distance maps to peek=1
const PEEK_THRESHOLD = 0.42; // commit if peek > this
const VELOCITY_THRESHOLD = 500; // px/s
const MIN_VELOCITY_DISTANCE = 20; // px
const MAX_PEEK_X = 24; // px drift right at peek=1
const MAX_PEEK_Y = 18; // px vertical pivot offset at peek=1
const MAX_SCALE_DOWN = 0.13; // card shrinks by 13% at peek=1
const CORNER_RADIUS = 20; // border-radius at peek=1
const MAX_DIM = 0.35; // backdrop opacity at rest
```

**Shared values used:**

| Value          | Purpose                                                                |
| -------------- | ---------------------------------------------------------------------- |
| `peek`         | 0→1, how far the card has "peeked" (both gesture sources drive this)   |
| `exit`         | 0→1, the final slide-out to the right when committing                  |
| `pivot`        | -1→1, vertical position of the drag (drives Y offset for depth feel)   |
| `nativeActive` | 0 or 1, blocks the pan gesture while Android system gesture is running |

**The animated style formula:**

```js
const screenStyle = useAnimatedStyle(() => {
  const p = peek.value;
  return {
    transform: [
      { translateX: p * MAX_PEEK_X + exit.value * width },
      { translateY: pivot.value * p * MAX_PEEK_Y },
      { scale: 1 - MAX_SCALE_DOWN * p },
    ],
    borderRadius: interpolate(
      p,
      [0, 0.05, 1],
      [0, CORNER_RADIUS, CORNER_RADIUS],
      Extrapolation.CLAMP,
    ),
    overflow: "hidden",
  };
});
```

**useFocusEffect for Android predictive back:**

```js
useFocusEffect(
  useCallback(() => {
    if (!PREDICTIVE_BACK_SUPPORTED || !isActive) return undefined;
    const track = (event) => {
      peek.value = event.progress;
      pivot.value = (event.touchY / height) * 2 - 1;
    };
    acquirePredictiveBack(token, {
      onStart: (event) => {
        nativeActive.value = 1;
        cancelAnimation(peek);
        track(event);
      },
      onProgress: (event) => {
        if (!isDismissing.current) track(event);
      },
      onCancel: cancel,
      onCommit: () =>
        commit(PREDICTIVE_BACK_HAS_PROGRESS ? EXIT_DURATION : ENTER_DURATION),
    });
    return () => releasePredictiveBack(token);
  }, [token, isActive, peek, pivot, nativeActive, cancel, commit]),
);
```

**Required imports:**

```js
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withSpring,
  cancelAnimation,
  runOnJS,
  interpolate,
  Extrapolation,
} from "react-native-reanimated";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import { useFocusEffect } from "@react-navigation/native";
import {
  PREDICTIVE_BACK_SUPPORTED,
  PREDICTIVE_BACK_HAS_PROGRESS,
  acquirePredictiveBack,
  releasePredictiveBack,
} from "../native/PredictiveBack";
```

---

### 8. `src/navigation/AppNavigator.jsx` — Modified

Sync the fallback back mode whenever the navigation state changes. In your file where you have managed all the navigation setup or insde App.jsx

```js
import {
  BACK_MODE_DEFAULT,
  BACK_MODE_SYSTEM,
  setFallbackBackMode,
} from '../native/PredictiveBack';

// Inside the AppNavigator component:
const syncBackMode = React.useCallback(() => {
  setFallbackBackMode(
    navigationRef.isReady() && navigationRef.canGoBack()
      ? BACK_MODE_DEFAULT
      : BACK_MODE_SYSTEM,
  );
}, []);

// On the NavigationContainer:
<NavigationContainer
  ref={navigationRef}
  onReady={syncBackMode}
  onStateChange={syncBackMode}
>
```

> [!IMPORTANT]
> `BACK_MODE_SYSTEM` (nothing claims back) at the root screen is what unlocks the OS _back-to-home_ animation. Without this call the app permanently claims back and that animation never plays.

---

## Dependencies Required

| Package                          | Version Used | Purpose                                                   |
| -------------------------------- | ------------ | --------------------------------------------------------- |
| `react-native-reanimated`        | ≥ 3.x        | Worklet animations (`useSharedValue`, `withSpring`, etc.) |
| `react-native-gesture-handler`   | ≥ 2.x        | `Gesture.Pan()`, `GestureDetector`                        |
| `@react-navigation/native`       | ≥ 6.x        | `useFocusEffect`, `navigationRef`                         |
| `androidx.activity:activity-ktx` | 1.9.3        | `BackEventCompat`, `OnBackPressedCallback`                |

No new JS packages required beyond what a standard React Navigation + Reanimated project already has.

---

## Three Back Modes Explained

| Mode      | `backCallback.isEnabled` | RN's own callback | Result                                            |
| --------- | ------------------------ | ----------------- | ------------------------------------------------- |
| `app`     | ✅                       | ✅                | Your JS `onCommit` fires; you control navigation  |
| `default` | ❌                       | ✅                | React Navigation / BackHandler handle it as usual |
| `system`  | ❌                       | ❌                | OS plays back-to-home animation (root screen)     |

---

## Ownership Model (Why It's Needed)

Only **one screen at a time** should respond to a back event. The JS-side `PredictiveBack.js` uses a lightweight token-based ownership model:

- `acquirePredictiveBack(token, handlers)` — the focused screen takes ownership.
- `releasePredictiveBack(token)` — the screen gives it up on blur.
- The check `owner !== token` inside `release` ensures that if two screens blur/focus in quick succession (the order is unspecified), the wrong screen can never accidentally release ownership.

---

## Checklist for Porting to a New Project

- [ ] Add `implementation("androidx.activity:activity-ktx:1.9.3")` to `build.gradle`
- [ ] Add `android:enableOnBackInvokedCallback="true"` to `AndroidManifest.xml`
- [ ] Create `PredictiveBackPackage.kt` (replace package name)
- [ ] Create `PredictiveBackModule.kt` (replace package name)
- [ ] Register `PredictiveBackPackage()` in `MainApplication.kt`
- [ ] Create `src/native/PredictiveBack.js`
- [ ] Wrap screens in `SwipeableScreen` component (or adapt the Reanimated logic into your existing wrapper)
- [ ] Add `syncBackMode` calls to `NavigationContainer` (`onReady` + `onStateChange`)
- [ ] Ensure `react-native-reanimated` and `react-native-gesture-handler` are set up (Babel plugin, `GestureHandlerRootView`, Reanimated plugin)

---

## Known Gotchas

> [!WARNING]
> **Do NOT use `OnBackInvokedCallback`** (the `android.window` API) directly. It has equal-priority LIFO behaviour and silently breaks React Native's own `BackHandler`.

> [!WARNING]
> **Re-add on every `app` mode switch.** The `OnBackPressedDispatcher` is a stack. `react-native-screens` adds its own callbacks as fragments mount. Re-adding ensures your callback stays on top.

> [!NOTE]
> **Reflection on RN's internal callback** is used for `system` mode. It's stable across RN 0.72–0.76 but could theoretically break if React Native changes its internal `ReactActivity` implementation. Failure is non-fatal — back still works, the OS animation just won't play.

> [!NOTE]
> On **iOS**, the `PredictiveBack.js` APIs are all no-ops (`PredictiveBackModule` is `null`). Only the drag-to-close pan gesture from `SwipeableScreen` applies.

# react-native-components

Native React Native modules for things the platform can already do, but React
Native cannot reach — Android's predictive-back gesture, the hardware step
counter, and the live-activity surfaces on both phones.

Each folder is an independently versioned npm package under the
[`@honeypathkar`](https://www.npmjs.com/~honeypathkar) scope. They share nothing
at runtime; install only what you need.

| Package | Version | Platform | What it does |
| --- | --- | --- | --- |
| [`react-native-live-update`](react-native-live-update) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-live-update.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-live-update) | iOS 16.1+ · Android | Live Activities and Live Updates behind one API |
| [`react-native-step-counter`](react-native-step-counter) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-step-counter.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-step-counter) | Android | Today's step count from the hardware sensor |
| [`react-native-predective-back-gesture`](react-native-predective-back-gesture) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-predictive-back-gesture.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-predictive-back-gesture) | Android 14+ · iOS | Predictive-back animations and swipe-to-close screens |

---

## The packages

### `@honeypathkar/react-native-live-update`

The "what's happening right now" surface on both phones — the Lock Screen and
Dynamic Island on iOS, the status-bar chip and the OEM surfaces built on it
(Samsung's Now Bar among them) on Android 16 — behind a single call.

Delivery runs, ride pickups, match scores, timers, uploads: anything with a
beginning, a middle and an end that a user wants to watch without opening the
app.

```js
import LiveUpdate from '@honeypathkar/react-native-live-update';

await LiveUpdate.start({
  id: order.id,
  name: `Order #${order.number}`,
  content: {
    title: `Order #${order.number}`,
    status: 'On way',
    progress: 0.66,
    endsAt: Date.now() + 12 * 60 * 1000,
  },
});
```

iOS uses ActivityKit and needs a widget extension added once per app; Android
builds a `NotificationCompat.ProgressStyle` notification and asks the system to
promote it, which needs nothing beyond installing the package. Whether a given
device actually shows the chip is the OS's decision, not the library's — the
package builds to the published contract and reports what is worth requesting.

**[Full documentation →](react-native-live-update#readme)**

### `@honeypathkar/react-native-step-counter`

Today's step count on Android, read from the low-power hardware
`Sensor.TYPE_STEP_COUNTER` rather than computed from the accelerometer.

The hardware counter is cumulative since boot and never resets at midnight, so
the package keeps a baseline and recalibrates it: at midnight via
`ACTION_DATE_CHANGED`, and after a reboot when the raw value comes back lower
than the stored baseline. WorkManager syncs in the background so the count is
right even if the app has not been opened.

```js
import { useStepCounter } from '@honeypathkar/react-native-step-counter';

const { steps } = useStepCounter();
```

**[Full documentation →](react-native-step-counter#readme)**

### `@honeypathkar/react-native-predictive-back-gesture`

Android 14's predictive-back gesture, wired to your screens. On API 34+ the card
follows the live OS gesture progress — shrinking, tilting, revealing the screen
behind it — and falls back to a slide-out animation on older versions and on the
hardware back button. The left-edge drag-to-close gesture works on both
platforms.

```jsx
import SwipeableScreen from '@honeypathkar/react-native-predictive-back-gesture';

<SwipeableScreen>
  <YourScreen />
</SwipeableScreen>;
```

Root screens stand down deliberately, so Android plays its own back-to-home
animation instead of competing with it.

**[Full documentation →](react-native-predective-back-gesture#readme)**

---

## Install

Each package is separate — there is no meta-package to install.

```sh
npm install @honeypathkar/react-native-live-update
npm install @honeypathkar/react-native-step-counter
npm install @honeypathkar/react-native-predictive-back-gesture
```

All three autolink. On iOS run `pod install` afterwards; on Android nothing else
is needed. Rebuild the app after installing — these are native modules, so a
Metro reload is not enough, and they do not work in Expo Go.

## Working on this repo

```sh
cd <package>
npm install
npm run build      # or `npm test` where there are tests
```

`lib/` is build output, generated on `prepare` and published to npm, and is not
tracked here. Versions are per package: bump and publish one without touching
the others.

## License

MIT © honeypathkar — see [LICENSE](LICENSE).

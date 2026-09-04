# react-native-components

Native React Native modules for things the platform can already do, but React
Native cannot reach — Android's predictive-back gesture, on-device speech
recognition, the hardware step counter, notification capture, and the
live-activity surfaces on both phones.

Each folder is an independently versioned npm package under the
[`@honeypathkar`](https://www.npmjs.com/~honeypathkar) scope. They share nothing
at runtime; install only what you need.

| Package | Version | Platform | What it does |
| --- | --- | --- | --- |
| [`react-native-speech-to-text`](react-native-speech-to-text) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-speech-to-text.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-speech-to-text) | iOS 13+ · Android 21+ | Speech recognition with silence detection and auto-stop |
| [`react-native-live-update`](react-native-live-update) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-live-update.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-live-update) | iOS 16.1+ · Android | Live Activities and Live Updates behind one API |
| [`react-native-predective-back-gesture`](react-native-predective-back-gesture) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-predictive-back-gesture.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-predictive-back-gesture) | Android 14+ · iOS | Predictive-back animations and swipe-to-close screens |
| [`react-native-step-counter`](react-native-step-counter) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-step-counter.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-step-counter) | Android | Today's step count from the hardware sensor |
| [`react-native-notification-listener`](react-native-notification-listener) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-notification-listener.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-notification-listener) | Android | System-level notification capture with payment parsing |
| [`react-native-soundbox-tts`](react-native-soundbox-tts) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-soundbox-tts.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-soundbox-tts) | Android | Multilingual payment announcements over the media stream |
| [`react-native-soundbox-engine`](react-native-soundbox-engine) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-soundbox-engine.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-soundbox-engine) | Android | Keeps the soundbox alive across reboots and OEM killers |
| [`react-native-battery-optimization`](react-native-battery-optimization) | [![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-battery-optimization.svg)](https://www.npmjs.com/package/@honeypathkar/react-native-battery-optimization) | Android | Doze exemption and per-OEM AutoStart settings |

---

## Cross-platform

### `@honeypathkar/react-native-speech-to-text`

Speech recognition on both phones — `SFSpeechRecognizer` with `AVAudioEngine` on
iOS, `SpeechRecognizer` on Android — with the part most wrappers leave out: it
knows when the user has stopped talking.

```jsx
import { useSpeechToText } from '@honeypathkar/react-native-speech-to-text';

const { isListening, transcript, audioLevel, startListening } = useSpeechToText({
  locale: 'en-IN',
  silenceTimeoutMs: 2500,
  onResult: text => console.log(text),
});
```

Android's own `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` is advisory and
widely ignored by OEM recognition services, so the pause is timed in the library
on both platforms — `silenceTimeoutMs` means the same thing everywhere.

The silence timer resets on new transcribed words rather than on microphone
level, which is the default for a reason: in a noisy room the input level never
drops below any threshold you pick, so a level-driven timer holds the mic open
forever. `silenceDetectionMode` switches to `audio` or `hybrid` where that suits
the environment better.

Also exposed: interim transcripts, a normalised `audioLevel` for meters and
waveforms, every recognition locale the device supports with display names, and
optional on-device recognition for offline use.

**[Full documentation →](react-native-speech-to-text#readme)**

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

Two animations are available. `card` is Material's predictive back: the screen
shrinks and drifts, staying visible so the user can see what they are going back
to. `slide` is the flatter, older idea — the screen tracks the finger across the
full width and walks off the edge, which is what most stock apps still do.

```jsx
<SwipeableScreen animation="slide">
  <YourScreen />
</SwipeableScreen>;
```

`card` is the default, so nothing changes for screens already using this.

Root screens stand down deliberately, so Android plays its own back-to-home
animation instead of competing with it.

**[Full documentation →](react-native-predective-back-gesture#readme)**

---

## Android sensors

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

---

## The soundbox stack

Four Android packages that add up to a merchant payment announcer: capture the
bank notification, say the amount out loud, and stay alive while doing it. They
are separate packages because each is useful on its own.

### `@honeypathkar/react-native-notification-listener`

A general-purpose `NotificationListenerService`, capturing notifications at the
system level even when the app is closed. Pulls text out of all seven slots
Android spreads it across — title, bigText, subText, summary, info and message
lines — rather than just the title.

Includes a UPI/bank payment parser (amount in paise, credit vs debit, UTR and
UPI reference, payer name), configurable filtering by package allowlist and
regex, multi-layer deduplication (key hashes, UTR keys, and a 90-second fuzzy
window), and SQLite persistence so nothing is lost offline.

**[Full documentation →](react-native-notification-listener#readme)**

### `@honeypathkar/react-native-soundbox-tts`

Payment announcements in eight Indian languages — English, Hindi, Marathi,
Bengali, Gujarati, Tamil, Telugu and Kannada — with sentence structures built per
language rather than translated word-for-word.

Speaks on `USAGE_MEDIA` so announcements are not swallowed by the silent/vibrate
switch, ducks background media with `TRANSIENT_MAY_DUCK`, queues with `QUEUE_ADD`
so simultaneous payments do not drop speech, and rebuilds the TTS instance when
the engine dies.

```js
import { SoundboxTTS } from '@honeypathkar/react-native-soundbox-tts';

await SoundboxTTS.init();
SoundboxTTS.speakPayment({ amountPaise: 45000, payerName: 'Honey' });
```

**[Full documentation →](react-native-soundbox-tts#readme)**

### `@honeypathkar/react-native-soundbox-engine`

The orchestrator: composes the notification listener and the TTS package, then
does the unglamorous work of surviving Android.

A `START_STICKY` foreground service with `specialUse`/`mediaPlayback` attributes
for Android 14+, a 15-minute alarm watchdog that re-arms and revives the service
when OEM power managers kill it, and a `RECEIVE_BOOT_COMPLETED` receiver so
monitoring resumes after a restart.

**[Full documentation →](react-native-soundbox-engine#readme)**

### `@honeypathkar/react-native-battery-optimization`

Doze-mode exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus
direct intents into the AutoStart screens of the manufacturers that need them —
Xiaomi/MIUI/HyperOS, Oppo/Realme/ColorOS, Vivo/iQOO/FuntouchOS,
Huawei/HarmonyOS/EMUI and Samsung One UI — instead of leaving users to find
those settings themselves.

Useful for anything that has to keep running: background services, location
tracking, the soundbox, the step counter.

**[Full documentation →](react-native-battery-optimization#readme)**

---

## Install

Each package is separate — there is no meta-package to install.

```sh
npm install @honeypathkar/react-native-speech-to-text
npm install @honeypathkar/react-native-live-update
npm install @honeypathkar/react-native-predictive-back-gesture
npm install @honeypathkar/react-native-step-counter
npm install @honeypathkar/react-native-notification-listener
npm install @honeypathkar/react-native-soundbox-tts
npm install @honeypathkar/react-native-soundbox-engine
npm install @honeypathkar/react-native-battery-optimization
```

The soundbox engine expects its two siblings alongside it:

```sh
npm install @honeypathkar/react-native-soundbox-engine \
            @honeypathkar/react-native-notification-listener \
            @honeypathkar/react-native-soundbox-tts
```

They all autolink. On iOS run `pod install` afterwards; on Android nothing else
is needed. Rebuild the app after installing — these are native modules, so a
Metro reload is not enough, and they do not work in Expo Go.

Packages that touch protected surfaces need permissions declared or granted:
speech-to-text needs `RECORD_AUDIO` plus `NSMicrophoneUsageDescription` and
`NSSpeechRecognitionUsageDescription` on iOS, and the notification listener needs
the user to enable it in system settings. Each package README covers its own
setup.

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

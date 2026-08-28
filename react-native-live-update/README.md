# @honeypathkar/react-native-live-update

[![npm](https://img.shields.io/npm/v/@honeypathkar/react-native-live-update)](https://www.npmjs.com/package/@honeypathkar/react-native-live-update)
[![license](https://img.shields.io/npm/l/@honeypathkar/react-native-live-update)](./LICENSE)

One API for the "what's happening right now" surface on both phones.

| | iOS | Android |
| --- | --- | --- |
| **Shows on** | Lock Screen, Dynamic Island | Status-bar chip, lock screen, and the OEM surfaces built on it — Samsung's Now Bar among them |
| **Built on** | ActivityKit Live Activities | `NotificationCompat.ProgressStyle` + promoted ongoing |
| **Needs** | iOS 16.1+, a widget extension | Nothing — API 36+ for the chip |
| **Remote updates** | APNs, per-activity token | Ordinary data push, then call `update()` |

Delivery runs, ride pickups, match scores, timers, uploads — anything with a
beginning, a middle and an end that a user wants to watch without opening the app.

```js
import LiveUpdate from '@honeypathkar/react-native-live-update';

await LiveUpdate.start({
  id: order.id,
  name: `Order #${order.number}`,
  content: {
    title: `Order #${order.number}`,
    message: 'Collect ₹137 on delivery',
    status: 'On way',              // the tiny one, for the island and the chip
    progress: 0.66,
    stages: [
      { id: 'pickup', title: 'Pickup' },
      { id: 'on_the_way', title: 'On the way', weight: 3 },
      { id: 'delivered', title: 'Delivered' },
    ],
    endsAt: Date.now() + 12 * 60 * 1000,
    deepLink: 'myapp://orders/8231',
  },
});
```

> **What the OS decides, and this package does not.** Dynamic Island, Android's
> status-bar chip, Samsung's Now Bar, Realme's Live Alerts and every other
> OEM live surface are controlled by the operating system. This package builds
> the notification and the activity to the published requirements and asks for
> promotion; whether any given device shows it, and where, is the system's call
> and cannot be guaranteed by a library. `getCapabilities()` tells you what is
> worth requesting — never what will appear.

---

## Install

```sh
npm install @honeypathkar/react-native-live-update
# or
yarn add @honeypathkar/react-native-live-update

cd ios && pod install
```

Autolinking handles both platforms. **Android needs nothing else** — skip to
[Usage](#usage). iOS needs a widget extension, for the reason below.

**Requirements**

| | |
| --- | --- |
| React Native | 0.68+ — works under both the old architecture and the New Architecture's interop layer |
| iOS | 16.1+ for Live Activities. The pod builds against 16.0, so it can go into an app with a lower floor and report unsupported below 16.1 |
| Android | `minSdk` 24. API 36 (Android 16) for the promoted status-bar chip; below that it degrades to an ongoing progress notification |
| Expo | Development builds only. **Not Expo Go** — this ships native code, and Expo Go cannot load it |

The pod name is `react-native-live-update`, unscoped: CocoaPods names cannot
contain `@` or `/`. Autolinking finds it by scanning the installed package, so
there is nothing to configure.

---

## iOS setup

### Why a widget extension is unavoidable

A Live Activity's UI is an `ActivityConfiguration`, which is a kind of
`WidgetConfiguration` — and Apple only allows those inside an extension declaring
`NSExtensionPointIdentifier = com.apple.widgetkit-extension`. There is no API to
render one from your app process.

That is not an implementation detail you can route around: the layout is compiled
ahead of time and rendered by SpringBoard **outside your app**, which is exactly
why it keeps showing with the app killed. Every library that does this — including
the Expo ones — requires the same extension. They just generate it during
`prebuild` instead of you adding it by hand.

You do this **once per app**.

### 1. Add the widget extension target

In Xcode: **File → New → Target → Widget Extension**. Name it (e.g.
`MyAppWidget`), untick *Include Configuration Intent* and *Include Live Activity*
(you are supplying both), and let Xcode embed it in your app.

### 2. Add the two Swift files to that target

Copy the layout out of the package and keep it — restyle it freely, it is yours:

```sh
cp node_modules/@honeypathkar/react-native-live-update/template/ios/LiveUpdateWidget.swift ios/MyAppWidget/
```

Then, in Xcode, add **both** of these to the widget target's *Compile Sources*:

- `ios/MyAppWidget/LiveUpdateWidget.swift` — the layout you just copied
- `node_modules/@honeypathkar/react-native-live-update/ios/LiveUpdateAttributes.swift` — **add
  it by reference, do not copy it**

> **Why by reference?** ActivityKit pairs a running activity with its layout by
> matching the `ActivityAttributes` *type*. The app compiles this file through the
> pod; the widget must compile the very same file. A copy that drifts by one field
> means your app starts activities the widget cannot render — and nothing appears,
> with no error anywhere to explain it.

Delete the `MyAppWidget.swift` and `MyAppWidgetBundle.swift` Xcode generated —
`LiveUpdateWidget.swift` already declares the `@main` bundle.

### 3. Declare support in the **app** target's `Info.plist`

```xml
<key>NSSupportsLiveActivities</key>
<true/>
<!-- Optional: lets the app update an activity from the background, which is the
     point if your statuses change while the phone is in a pocket. -->
<key>NSSupportsLiveActivitiesFrequentUpdates</key>
<true/>
```

Without the first key iOS refuses to start any activity, and the error it gives
you is not worth reading.

### 4. Deployment targets

The widget target must be **iOS 16.1 or higher** (ActivityKit's floor). An
extension may require a *higher* minimum than its host app, never a lower one, so
your app target can stay where it is.

---

## Android: how it actually works

There is no Samsung SDK, no Realme SDK, and nothing to reverse engineer. Android
16 (API 36) added **Live Updates**: an ongoing notification that *asks* to be
promoted, which the system then renders as a chip in the status bar and a card on
the lock screen. OEM surfaces read that same API — Samsung's Now Bar in One UI 8
consumes exactly this. So the entire job is building one notification correctly.

The framework's own `Notification.hasPromotableCharacteristics()` is the whole
contract, and it is an AND of all of these:

```
isRequestPromotedOngoing() && isOngoingEvent() && hasTitle()
    && hasPromotableStyle() && !isGroupSummary()
    && !containsCustomViews() && !isColorizedRequested()
```

Two of them are counter-intuitive and cost real time to discover:

- **The app has to ask**, via `setRequestPromotedOngoing(true)`. There is no
  implicit promotion, however correct the rest of the notification is.
- **It must not be colorized.** `setColorized(true)` actively disqualifies it —
  the opposite of the instinct, since colorized is what an ongoing *media*
  notification wants.

The package also declares `POST_PROMOTED_NOTIFICATIONS` in its manifest, which
merges into your app. Without it the system declines the promotion silently and
leaves an ordinary notification, logging nothing.

**One code path, all versions.** Everything goes through `NotificationCompat` from
androidx.core 1.17, not the platform builder. The compat library carries the whole
Live Update surface and picks the implementation per device: on API 36+ it calls
the real `Notification.ProgressStyle`, and below it collapses the same track into
`setProgress(max, progress, indeterminate)`. There are no `SDK_INT` branches in
this package's notification code and no separate legacy builder — older Android
gets an ordinary ongoing progress notification from the identical call.

### Verifying it on a device

The module logs the system's own verdict for every post:

```sh
adb logcat -s LiveUpdate
# D LiveUpdate: posted 8000 promotable=true
```

`promotable=false` means the notification is missing one of the characteristics
above — that is a bug in your content, and it is worth reporting. `promotable=true`
while nothing appears in the chip means the device or OEM is not promoting it,
which no API reports and no app can force.

### Notification channel

One channel, `live_update`, created on first use with `IMPORTANCE_DEFAULT`, no
sound, and no vibration. Rename it before your first `start()`:

```js
await LiveUpdate.configureNotifications({
  channelName: 'Order tracking',
  channelDescription: 'Progress for orders on their way to you',
});
```

Android freezes a channel's importance at creation and ignores every later change
— only the user can move it after that. Note that `importance: 'low'` makes the
notification ineligible for promotion, so it trades away the chip and everything
built on it.

### What this package deliberately does not do

- **No foreground service.** A live update is a display, not a background task.
  Nothing here needs a service to stay running, and declaring the permission
  would make every app that installs this answer for it at review. If your app
  separately needs one for real background work, run it yourself and call
  `update()` from it.
- **No location.** The package renders state you give it and never sources any.
  It requests no location permission and never will.
- **No Firebase.** See [remote updates](#android--any-push-you-already-have).

---

## Usage

```js
import LiveUpdate from '@honeypathkar/react-native-live-update';

// Is it possible, and has the user allowed it?
const { supported, enabled, reason } = await LiveUpdate.isSupported();

// Start. Returns once the system has accepted it.
await LiveUpdate.start({
  id: 'order-8231',                 // your id — reusing it updates, never duplicates
  name: 'Order #8231',              // static, never changes for this activity
  content: { title: 'Order #8231', status: 'To shop', progress: 0.33 },
});

// Change what it shows. Content replaces, it does not merge.
await LiveUpdate.update('order-8231', {
  title: 'Order #8231',
  message: 'Collect ₹137 on delivery',
  status: 'On way',
  progress: 0.66,
});

// Finish. The delay leaves the final state readable before it disappears.
await LiveUpdate.end('order-8231', 4000);
```

### The content schema

Every field is optional except `title`.

| field | type | shown as |
| --- | --- | --- |
| `title` | `string` | Headline — Lock Screen and expanded island |
| `message` | `string` | Supporting line under the title |
| `status` | `string` | **Keep it tiny.** Compact island and Android's status-bar chip; Android cuts it around 7 characters |
| `progress` | `number` 0–1 | Position along the track. Omit for indeterminate |
| `stages` | `Stage[]` | Splits the track into legs with milestones between them |
| `endsAt` | `number` (ms epoch) | A **live-ticking countdown**, rendered by the OS |
| `icon` | `string` | SF Symbol name (iOS) / drawable name (Android) |
| `trackerIcon` | `string` | **Android only.** Icon that rides along the track |
| `startIcon` | `string` | **Android only.** Icon pinned at the start of the track |
| `endIcon` | `string` | **Android only.** Icon pinned at the end of the track |
| `color` | `string` | Accent, `#RRGGBB` |
| `deepLink` | `string` | Where a tap lands. See [deep linking](#deep-linking) |
| `actions` | `Action[]` | **Android only.** Up to 3 buttons |

A `Stage` is `{ id, title, weight?, color?, milestone? }`. `weight` is relative
and defaults to 1, so three plain stages split the track in thirds; give the
long leg `weight: 3` and it takes three-quarters. Android renders these as
`ProgressStyle` segments; iOS draws the matching segmented bar.

### Icons on the track

The track carries exactly three: `startIcon`, `trackerIcon`, `endIcon` — where
the journey began, what is moving, and the destination.

```js
content: {
  startIcon: 'ic_track_store',      // a shop
  trackerIcon: 'ic_track_scooter',  // rides along as progress advances
  endIcon: 'ic_track_home',         // the customer's door
}
```

**A per-stage glyph is not possible.** Android's `ProgressStyle.Point` holds a
position, an id and a colour and nothing else — there is no icon slot, so a tick
on "Picked up" or a pan on "Preparing" cannot be drawn. Put those states in
`status` and `message`, which are legible at a glance and readable by
accessibility services.

Each icon takes either:

- **A drawable name** in your app's resources — the better option. A vector
  drawable is rendered at whatever density the system asks for, and costs
  nothing to pass. This is also how you vary icons per app: ship different
  drawables under the same names in your customer and courier builds, and the
  JS never changes.
- **An absolute path** to a PNG or JPEG, for icons that cannot be compiled in —
  fetched at runtime, or rendered by your app. The file is decoded and
  downscaled before it is attached: notifications cross a Binder transaction
  with a hard size limit, and a full-resolution image there is a crash, not a
  bad-looking icon.

#### Using your Lucide icons

You cannot pass `<Bike />` to a live update — the notification is drawn by
SystemUI in a different process, which can only be handed a resource, a bitmap
or a URI, never a React component. But you can use the *same icon*, because the
geometry behind it is plain SVG and an Android vector drawable is the same idea
in a different syntax. The package ships a converter:

```sh
npx lucide-to-drawable bike --out android/app/src/main/res/drawable/ic_track_bike.xml
```

```js
content: { trackerIcon: 'ic_track_bike' }
```

It reads `lucide-react-native` (or `lucide-react`, `lucide`, `lucide-static`)
from your own `node_modules`, so you get the version your screens already
render rather than a copy pinned in here. Lucide is stroke-based — 2px strokes,
round caps, no fills — and vector drawables support all of that, so the result
is the icon itself and not an approximation. `--color` and `--stroke` override
the defaults; circles, lines, rects, polylines and polygons are all converted
to path data, since `pathData` is the only geometry a vector drawable has.

Re-run it when you change icons and commit the output. Any other SVG works too,
through Android Studio's **Vector Asset** dialog.

### Buttons

```js
content: {
  actions: [
    { id: 'delivered', title: 'Mark delivered', deepLink: 'myapp://orders/8231' },
  ],
}
```

Android shows **at most three** and drops the rest, which is why more than
three is a validation error rather than a surprise on a device. Each one opens
your app at its `deepLink` — the only thing a button can reliably do when the
JS runtime may not be running, and on a backgrounded app it usually is not. An
action that must complete *without* opening the app needs a native receiver in
your own code.

Restraint reads better than options: one clear next step beside a full-width
progress bar is the shape Google Maps uses for navigation, and every extra
button takes width from the title.

Ignored on iOS — ActivityKit buttons are App Intents compiled into the widget
extension, which is a different mechanism rather than a different spelling of
this one.

### Keeping it on screen

```js
await LiveUpdate.start({ id, name, content, persistent: true });
```

**Android has had no non-dismissible notification since 14.** The platform
deliberately made ongoing notifications — foreground-service ones included —
user-dismissible, exempting only call, media and device-policy notifications.
Nothing an ordinary app sets prevents a swipe, and `ongoing: true` has not meant
"cannot be removed" for several releases.

`persistent` does the next best thing: it notices the swipe and posts the update
again. Reasonable for a courier app where the run display is a condition of the
shift; obnoxious in a consumer app. It stops the moment you call `end()` — the
activity is gone from the store by then, so a finished delivery stays finished.
Android-only: iOS gives an app no say in whether a Live Activity can be cleared,
and no callback when it is.

`milestone` (Android only, off by default) draws a marker where that stage
*begins*. Leave it off for most journeys: Android renders a milestone as a
filled square sitting on the track, which is heavy next to the thin bar, and it
lands on the gap that already separates two segments — so a marker on every
boundary restates what the gap says and does it more loudly. Turn it on for the
one or two stops that are genuinely events. It is ignored on the first stage,
whose start is the start of the track.

`progress` positions the marker across the *whole* track — the stages only
describe how it is divided. There is no "completed" flag on a stage on purpose:
which ones are done follows from `progress`, and two sources of truth for the
same fact is how a bar ends up disagreeing with its own labels.

> This is a fixed schema, not free-form data, and it has to be. iOS lays the
> activity out at compile time — SwiftUI cannot render keys it has never seen. To
> add a field, add it to `LiveUpdateAttributes.ContentState` and to your widget's
> layout, then rebuild both targets.

**Prefer `endsAt` over a ticking `update()`.** The system counts down on its own
with no further calls, and every update costs you budget (see [Limits](#limits)).

### API

| | |
| --- | --- |
| `isSupported()` | `{ supported, enabled, reason? }`. Never rejects |
| `getCapabilities()` | the full picture — see below |
| `configureNotifications(config)` | Android channel naming. No-op on iOS |
| `start({ id, name, content, persistent? })` | `{ id, pushToken? }` — starting an existing `id` updates it |
| `update(id, content, options?)` | replaces what is on screen |
| `end(id, dismissAfterMs = 0)` | `0` removes it at once |
| `getRunning()` | `string[]` of live ids — survives app restarts |
| `endAll()` | ends everything this app started |
| `addPushTokenListener(fn)` | iOS only; see below |

`getRunning()` reconciles against the system's own list rather than trusting the
package's records, so a reboot — which clears every notification while leaving
records intact — does not leave you with ghost ids. Call `endAll()` on logout: an
activity outlives the app, and a signed-out phone showing the previous user's
delivery is a support ticket.

Every failure is a `LiveUpdateError` with a `code` you can branch on:
`NOT_SUPPORTED`, `PERMISSION_DENIED`, `NOT_FOUND`, `INVALID_CONTENT`,
`NATIVE_MODULE_UNAVAILABLE`, `START_FAILED`, `UPDATE_FAILED`.

### Capabilities

```js
const caps = await LiveUpdate.getCapabilities();
// {
//   platform: 'android',
//   osVersion: '16',
//   supported: true,
//   enabled: true,
//   stagesSupported: true,        // false below API 36 — the track flattens
//   lockScreenSupported: true,
//   pushUpdatesSupported: false,  // Android has no per-activity push channel
//   promotedSurface: { available: true, type: 'statusBarChip' },
//   notificationPermission: 'granted',
//   device: { manufacturer: 'samsung', model: 'SM-S928B' },
// }
```

`promotedSurface.available` means "this OS build exposes the surface and your app
is allowed to ask for it". It is not a promise that anything will appear.

`device` is there so you can log which handsets your users are on and decide for
yourself. **The package draws no conclusions from it.** There is no `isSamsung()`
here and no manufacturer table: no OEM publishes a third-party contract for its
live surface, so mapping a manufacturer to a guarantee would be a guess wearing an
API's clothes — and wrong on every build that predates the feature or has it
switched off.

On iOS, `promotedSurface` reports the Dynamic Island as available whenever
ActivityKit is, which is as precise as iOS allows: no public API says whether this
particular iPhone has the hardware. On one that does not, the same activity is
drawn on the Lock Screen instead, so there is no branch to take.

### Throttling

```js
await LiveUpdate.update(id, content, { throttleMs: 1000 });
```

Trailing-edge, per id: the first call goes straight through, calls inside the
window are coalesced, and the newest value is sent when the window closes.
Worth setting when your updates come from a stream you do not control — a
location feed pushing a new percentage every second. Both systems ration updates,
and an app that spends its budget on invisible one-pixel changes has none left for
the ones that matter. Leave it off for updates that are already meaningful.

`end()` and `start()` drop anything queued for that id, so a stale update cannot
land after the activity is finished and put it back on screen.

### Deep linking

Set `content.deepLink` to a URL your app already handles, and read it with React
Native's `Linking` as you would any other cold or warm start. No navigation
library is assumed.

```js
content: { title: 'Order #8231', deepLink: 'myapp://orders/8231' }
```

You must register the scheme yourself — the package cannot know it. On Android
add an `<intent-filter>` to your main activity; on iOS add a `CFBundleURLTypes`
entry. On Android the intent is pinned to your own package, so another app that
registered the same scheme cannot intercept the tap; if the link resolves to
nothing, the notification opens your app's launch screen and the reason is logged
under the `LiveUpdate` tag. Omit `deepLink` to always open at the launch screen.

---

## Remote updates

### iOS — APNs, per activity

ActivityKit issues a push token **scoped to one activity**. It is not your device
token, it is not an FCM token, and **FCM cannot deliver these** — you must talk to
APNs directly.

```js
useEffect(() => {
  const sub = LiveUpdate.addPushTokenListener(({ id, token }) => {
    api.registerActivityToken(id, token);   // tokens rotate; always send the latest
  });
  return () => sub.remove();
}, []);
```

Then from your server:

```http
POST https://api.push.apple.com/3/device/<activity-token>
authorization:  bearer <JWT signed with your .p8 key>
apns-push-type: liveactivity
apns-topic:     com.your.app.push-type.liveactivity
apns-priority:  10
```

```json
{
  "aps": {
    "timestamp": 1735689600,
    "event": "update",
    "content-state": {
      "title": "Order #8231",
      "message": "Arriving now",
      "status": "Close",
      "progress": 0.9,
      "endsAt": 1735690000
    }
  }
}
```

Three things bite people here:

1. **`content-state` keys must match `ContentState` exactly** — same names, same
   types. A mismatch is dropped silently.
2. **`endsAt` is in SECONDS over push**, not milliseconds. The JS API takes
   milliseconds and converts; a raw APNs payload is decoded straight into Swift's
   `Double` seconds.
3. **To end remotely**, send `"event": "end"` with an optional
   `"dismissal-date"` (epoch seconds).

Use `api.sandbox.push.apple.com` for debug builds.

### Android — any push you already have

An Android live update is a local notification, so there is no token to register
and nothing to integrate. Send yourself any ordinary data push and call `update()`
from the handler.

**But do not assume JS is running.** Android kills backgrounded apps freely, and a
headless JS task is not guaranteed to start in time. For updates that must land,
call the native manager directly from your messaging service — it is public, and
it is the same code path the bridge uses:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val manager = LiveUpdateManager(this)
    manager.update(
      message.data["id"]!!,
      LiveUpdateContent(
        title = message.data["title"]!!,
        progress = message.data["progress"]?.toDouble(),
        // …
      ),
    )
  }
}
```

The state needed to rebuild the notification — the name, the last content, the
notification id — is persisted natively, so this works after the process has been
killed and restarted, which is exactly when a push arrives. Firebase is **not** a
dependency of this package; this is the seam, and you bring your own transport.

---

## Limits

- **Dynamic Island** needs an iPhone 14 Pro or later. Everywhere else the same
  activity still appears on the Lock Screen — there is nothing to handle.
- **iOS ends an activity after 8 hours** of activity, and removes it from the Lock
  Screen after 12. Long-running things should be restarted, not left.
- **Users can switch Live Activities off per app** in Settings. `isSupported()`
  reports it as `enabled: false`; `start()` rejects with `PERMISSION_DENIED`.
- **iOS caps `ContentState` at 4KB.** The update is rejected, not the activity, so
  an oversized payload looks like an activity that silently stopped moving.
- **Android below API 36** posts an ordinary ongoing notification with a plain
  progress bar. It shows in the shade, but not in the status-bar chip or any OEM
  surface built on it — Samsung's Now Bar reads the Android 16 promoted-ongoing
  API, which is why One UI 7 devices cannot show third-party apps there at all.
- **Android 13+ needs `POST_NOTIFICATIONS`.** The library never asks for it; your
  app owns that prompt. Without it `start()` rejects with `PERMISSION_DENIED`
  rather than posting into the void.
- **A dismissed Android update comes back if you update it.** From Android 14 the
  user can swipe an ongoing notification away; the next `update()` re-posts it,
  because the app is the source of truth for whether the delivery is still
  happening. iOS treats a dismissal as the end of the activity and rejects the
  update. Call `end()` when the work finishes and the two behave the same.
- Updates are rate-limited by both systems. Budget them; lean on `endsAt`.
- **Nothing sensitive belongs in the content.** It renders on a locked screen and,
  on Android, is readable by anything holding notification-listener access. No
  tokens, no full names, no addresses you would not print on a postcard.

---

## Troubleshooting

**Nothing appears on iOS, no error.** Almost always the attributes type. Confirm
the widget target compiles `LiveUpdateAttributes.swift` *from the package* and not
a copy, and that `NSSupportsLiveActivities` is in the **app's** Info.plist, not
the widget's.

**`Cycle inside <YourApp>; building could produce unreliable results`.** The
"Embed Foundation Extensions" phase is running after a script phase that declares
no output files — React Native's "Bundle React Native code and images" and the
CocoaPods phases all qualify. Xcode reads an output-less script as "may write
anywhere in `BUILT_PRODUCTS_DIR`", and the embed writes there too. Drag the embed
phase up so it sits directly after **Resources**.

**`built for newer 'iOS-simulator' version (16.0) than being linked (15.1)`.**
Your app target's deployment target is below your pods'. Raise the app to match
the `platform :ios` line in your Podfile.

**`start()` rejects with `PERMISSION_DENIED` on iOS.** The user turned Live
Activities off for your app: Settings → your app → Live Activities.

**Android shows it in the shade but not the chip.** Filter logcat for
`LiveUpdate` and read `promotable=`. `false` means the notification is missing a
required characteristic — check that the channel is not set to `low`.
`true` while nothing appears means the device or OEM is not promoting it; check
`getCapabilities()` for `promotedSurface.available: false` (the user revoked
promoted notifications) or an `osVersion` below 16.

**Android stages render as a plain bar.** `stagesSupported: false` — the device is
below API 36, where the compat library flattens the segmented track. Working as
intended.

**Tapping the notification does nothing on Android.** The deep link resolves to no
activity in your app. `adb logcat -s LiveUpdate` prints the URL and the reason;
add an `<intent-filter>` for its scheme.

---

## License

MIT

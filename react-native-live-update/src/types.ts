/**
 * The shape of everything this package can put on screen, on either platform.
 *
 * Deliberately a fixed schema rather than free-form data. iOS compiles its
 * layout into a widget extension ahead of time — SwiftUI cannot lay out keys it
 * has never seen — so an "anything goes" object would be unrenderable there.
 * Android has the mirror-image constraint: `Notification.ProgressStyle` draws a
 * track built from typed segments and points, not from arbitrary JSON.
 *
 * These fields are the intersection: the shapes both systems draw natively.
 */

/** One leg of the journey — "Picked up", "On the way", "Delivered". */
export interface LiveUpdateStage {
  /** Stable identifier. Unique within one activity. */
  id: string;
  /** Human label. Android reads it out for accessibility; iOS can show it. */
  title: string;
  /**
   * Relative width of this leg on the track. Defaults to 1, meaning every
   * stage takes an equal share. Use it when the legs are genuinely uneven —
   * a 20-minute ride to the restaurant and a 5-minute hop to the door.
   */
  weight?: number;
  /** `#RRGGBB` for this leg once it has been reached. Defaults to the accent. */
  color?: string;
  /**
   * **Android only.** Draw a marker where this stage *begins*.
   *
   * Off by default, and worth leaving off for most journeys. Android renders a
   * milestone as a filled square sitting on the track, which is heavy next to
   * the thin bar — and the gap between two segments already reads as a
   * boundary, so a marker on every one is noise. Turn it on for the one or two
   * stops that are genuinely events: arriving at the restaurant, not "the next
   * leg starts here".
   *
   * Ignored on the first stage, whose start is the beginning of the track.
   */
  milestone?: boolean;
}

/** A button on the live update. */
export interface LiveUpdateAction {
  /** Stable identifier, so you can tell taps apart. */
  id: string;
  /**
   * Button label. Keep it to a word or two — Android gives an action very
   * little room and truncates without ceremony.
   */
  title: string;
  /**
   * Where tapping it takes the user. Same rules as
   * {@link LiveUpdateContent.deepLink}: your app must already handle the URL.
   * Without one, the button opens the app at its launch screen.
   */
  deepLink?: string;
}

export interface LiveUpdateContent {
  /** Headline. iOS: the leading text on the Lock Screen and expanded island. */
  title: string;
  /** Supporting line under the title. */
  message?: string;
  /**
   * Short status word — "Picked up", "Arriving". Rendered in the compact
   * Dynamic Island and in Android's status-bar chip, so keep it very short:
   * Android truncates it at around 7 characters.
   */
  status?: string;
  /** 0..1. Drives the progress track on both platforms. Omit for indeterminate. */
  progress?: number;
  /**
   * The journey, in order. Android draws these as the segments and milestone
   * points of a `Notification.ProgressStyle` track — the dotted line with a
   * moving marker you see on a delivery. iOS draws a segmented bar.
   *
   * `progress` positions the marker along the whole track; the stages only
   * describe how the track is divided. Omit for a plain bar.
   */
  stages?: LiveUpdateStage[];
  /**
   * Epoch milliseconds the activity is counting down to — an ETA. Both
   * platforms render a live-ticking timer from this without further updates,
   * which is the cheapest way to keep it fresh.
   */
  endsAt?: number;
  /**
   * Leading glyph. iOS: an SF Symbol name. Android: a drawable name in your
   * app's resources, falling back to the app icon if it does not resolve.
   */
  icon?: string;
  /**
   * **Android only.** The marker that rides along the progress track — the
   * scooter on a delivery. Ignored on iOS, where the bar has no marker.
   *
   * Either a drawable name in your app's resources (a vector drawable is
   * ideal, and is what an SVG becomes once imported), or an absolute path to
   * a PNG/JPEG the app downloaded or rendered at runtime. See
   * {@link LiveUpdateContent.startIcon} for why a path is sometimes the only
   * option.
   */
  trackerIcon?: string;
  /**
   * **Android only.** Icon pinned at the start of the track — where the
   * journey began. A shop for a delivery, a station for a journey.
   *
   * Takes a drawable name or an absolute image path, like
   * {@link LiveUpdateContent.trackerIcon}.
   *
   * These three — `startIcon`, `trackerIcon`, `endIcon` — are the only icons
   * the track can carry. Android's `ProgressStyle.Point` holds a position, an
   * id and a colour, and nothing else, so a per-milestone glyph (a tick on
   * "Picked up", a pan on "Preparing") is not something the platform can draw.
   * Those states belong in `status` and `message`, which are read aloud by
   * accessibility services and legible at a glance; the icons carry the
   * endpoints and the thing that moves.
   *
   * A drawable name is resolved in your app's resources, so it must be
   * compiled in. React components cannot be used — an icon library like
   * lucide-react-native renders inside your app's view hierarchy, while the
   * notification is drawn by SystemUI in a different process, which can only
   * be handed a resource, a bitmap or a URI. Export the glyph to an SVG and
   * import it through Android Studio's *Vector Asset* dialog, or render it to
   * a PNG at runtime and pass that file's path.
   */
  startIcon?: string;
  /**
   * **Android only.** Icon pinned at the end of the track — the destination.
   * Takes a drawable name or an absolute image path.
   */
  endIcon?: string;
  /** Accent colour, `#RRGGBB`. Tints the track and the status-bar chip. */
  color?: string;
  /**
   * Where tapping it should take the user — `myapp://orders/8231`.
   *
   * Your app must already handle the URL: register the scheme on both
   * platforms and read it through React Native's `Linking`. Omit to open the
   * app at its launch screen.
   */
  deepLink?: string;
  /**
   * **Android only.** Buttons along the bottom — "End trip", "Call", "Mark
   * delivered".
   *
   * At most three; Android silently drops the rest. Each one opens your app at
   * its `deepLink`, which is the only thing a button can reliably do when the
   * JS runtime may not be running — and on a backgrounded app it usually is
   * not. An action that must complete without opening the app needs a native
   * receiver in your own code.
   *
   * Ignored on iOS: ActivityKit buttons are App Intents compiled into the
   * widget extension, which is a different mechanism, not a different spelling
   * of this one.
   */
  actions?: LiveUpdateAction[];
}

export interface StartOptions {
  /**
   * Your identifier for the thing being tracked — an order id. Reusing it
   * replaces the existing update rather than stacking a second one.
   */
  id: string;
  /** Static label that never changes for the life of the activity. */
  name: string;
  content: LiveUpdateContent;
  /**
   * **Android only.** Put the live update back if the user swipes it away.
   *
   * Off by default, and worth thinking about before switching on. It is not a
   * "non-dismissible" flag, because Android has not had one since 14: the
   * platform deliberately made ongoing notifications — foreground-service ones
   * included — user-dismissible, and exempts only call, media and
   * device-policy notifications. Nothing an ordinary app sets can prevent a
   * swipe.
   *
   * What this does instead is notice the swipe and post it again. That is
   * legitimate for a courier app where the tracking display is a condition of
   * the shift, and obnoxious in a consumer app, where it reads as a
   * notification that will not take no for an answer. It also stops the moment
   * you call `end()` — the activity is gone from the store by then, so a
   * finished delivery stays finished.
   *
   * Lives here rather than in `content` on purpose: `update()` replaces
   * content wholesale, so a flag kept there would switch itself off the first
   * time a caller sent an update without it.
   */
  persistent?: boolean;
}

export interface UpdateOptions {
  /**
   * Ignore this update if the previous one landed less than this many
   * milliseconds ago, sending it once the window passes instead. The most
   * recent value always wins; intermediate ones are dropped.
   *
   * Worth setting when your updates come from a stream you do not control — a
   * location feed pushing a new percentage every second. Both systems ration
   * updates, and an app that spends its budget on invisible one-pixel changes
   * has none left for the ones that matter. Leave it off for updates that are
   * already meaningful.
   */
  throttleMs?: number;
}

export interface LiveUpdateHandle {
  /** The id you passed in. */
  id: string;
  /**
   * iOS only, and only once ActivityKit has issued it — the per-activity APNs
   * token for remote updates. It is NOT the device token, it is scoped to this
   * one activity, and it can arrive after start() resolves: prefer
   * addPushTokenListener over reading this field.
   */
  pushToken?: string;
}

export interface LiveUpdateSupport {
  /** Whether the platform can show one at all. */
  supported: boolean;
  /**
   * Whether the user has them switched on. iOS users can disable Live
   * Activities per app in Settings, and Android users can demote a channel.
   */
  enabled: boolean;
  /** Why not, when supported is false — useful in a log, not in the UI. */
  reason?: string;
}

export type PermissionStatus = 'granted' | 'denied' | 'unknown';

/**
 * The system surface a live update can be promoted onto — the Dynamic Island,
 * Android 16's status-bar chip, and whatever an OEM builds on top of that chip
 * (Samsung's Now Bar reads exactly this API).
 */
export interface PromotedSurface {
  /**
   * Whether this OS build exposes the surface and this app is allowed to ask
   * for it.
   *
   * NOT a promise that anything will appear. Promotion is the system's
   * decision, made per notification and re-made as conditions change; no
   * public API forces it and none reports it after the fact. Treat `true` as
   * "worth requesting", never as "the user will see it".
   */
  available: boolean;
  /** `'dynamicIsland'` on iOS, `'statusBarChip'` on Android 16+. */
  type?: 'dynamicIsland' | 'statusBarChip';
}

export interface LiveUpdateCapabilities {
  platform: 'ios' | 'android';
  /** `'18.2'`, `'16'` — the OS version, as the OS reports it. */
  osVersion: string;
  supported: boolean;
  enabled: boolean;
  /** Whether the segmented track from `content.stages` renders as designed. */
  stagesSupported: boolean;
  /** Whether it appears on the lock screen. True on every supported version. */
  lockScreenSupported: boolean;
  /** iOS: APNs to a per-activity token. Android: false — see the README. */
  pushUpdatesSupported: boolean;
  promotedSurface: PromotedSurface;
  notificationPermission: PermissionStatus;
  /**
   * Android only. Present so you can log which handsets your users are on and
   * decide for yourself. The package draws no conclusions from it: no OEM
   * publishes a contract for its live-update surface, so mapping a
   * manufacturer to a guarantee would be a guess dressed as an API.
   */
  device?: {
    manufacturer: string;
    model: string;
  };
  /** Present when something is degraded. Diagnostic, not user-facing copy. */
  reason?: string;
}

export interface NotificationConfig {
  /** Defaults to `live_update`. */
  channelId?: string;
  /** Shown to the user in system settings. Defaults to "Live updates". */
  channelName?: string;
  /** Also shown in settings, under the name. */
  channelDescription?: string;
  /**
   * `'default'` (the default) or `'low'`.
   *
   * A live update is a persistent status display, not an alert, so neither
   * level makes a sound. `'low'` keeps it out of the status bar on older
   * Android — but on Android 16 it also makes the notification ineligible for
   * promotion, so the chip and anything built on it are forfeited.
   */
  importance?: 'default' | 'low';
}

export interface PushTokenEvent {
  id: string;
  token: string;
}

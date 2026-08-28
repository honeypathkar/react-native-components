import type { EmitterSubscription } from 'react-native';
import { LiveUpdateErrorCode, toLiveUpdateError } from './errors';
import { Native, emitter, noopSubscription } from './native';
import type {
  LiveUpdateCapabilities,
  LiveUpdateContent,
  LiveUpdateHandle,
  LiveUpdateSupport,
  NotificationConfig,
  PushTokenEvent,
  StartOptions,
  UpdateOptions,
} from './types';
import { validateContent, validateId, validateStartOptions } from './validate';

export * from './types';
export { LiveUpdateError, LiveUpdateErrorCode } from './errors';

/**
 * Can this device show a live update, and has the user allowed it?
 *
 * iOS needs 16.1+; the Dynamic Island itself needs an iPhone 14 Pro or later,
 * but on older iPhones the same activity still appears on the Lock Screen, so
 * this reports `supported: true` there. Android needs the notification
 * permission, and only Android 16+ promotes it to the status-bar chip.
 *
 * For the detail behind the two booleans, use {@link getCapabilities}.
 */
export function isSupported(): Promise<LiveUpdateSupport> {
  return Native.isSupported().catch((error) => {
    // The one call that must never throw: it is what a caller uses to find out
    // whether the package works at all, including "it is not linked".
    return {
      supported: false,
      enabled: false,
      reason: toLiveUpdateError(error).message,
    };
  });
}

/**
 * Everything the platform will tell us about what it can draw, and on which
 * surfaces.
 *
 * Read `promotedSurface.available` as "worth requesting", never as "the user
 * will see it" — promotion is decided by the system, per notification, and
 * re-decided as conditions change.
 */
export function getCapabilities(): Promise<LiveUpdateCapabilities> {
  return Native.getCapabilities().catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.NOT_SUPPORTED);
  });
}

/**
 * Android only: name and tune the notification channel before the first
 * `start()`.
 *
 * Android freezes a channel's importance the moment it is created and ignores
 * every later change — only the user can move it after that. So this has to
 * run before the first live update of a fresh install, or it does nothing.
 * A no-op on iOS.
 */
export function configureNotifications(
  config: NotificationConfig,
): Promise<void> {
  return Native.configureNotifications(config).catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.START_FAILED);
  });
}

/**
 * Start a live update. Resolves once the system has accepted it.
 *
 * `async` so that a validation failure comes back as a rejection like every
 * other error. A promise-returning function that throws synchronously walks
 * straight past the caller's `.catch()`, which is a nasty way to learn that a
 * title was empty.
 */
export async function start(options: StartOptions): Promise<LiveUpdateHandle> {
  validateStartOptions(options);
  clearPending(options.id);
  return Native.start(options.id, options.name, options.content, {
    persistent: options.persistent ?? false,
  }).catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.START_FAILED);
  });
}

/**
 * Change what an existing live update shows.
 *
 * `content` replaces the previous content wholesale — it is not merged. Send
 * every field you want on screen, not just the ones that changed.
 */
export async function update(
  id: string,
  content: LiveUpdateContent,
  options?: UpdateOptions,
): Promise<void> {
  validateId(id);
  validateContent(content);

  const throttleMs = options?.throttleMs ?? 0;
  if (throttleMs > 0) return throttled(id, content, throttleMs);
  return send(id, content);
}

/**
 * Finish it.
 *
 * `dismissAfterMs` leaves the final state on screen for a moment — "Delivered"
 * is worth reading before it disappears. 0 removes it immediately.
 */
export async function end(id: string, dismissAfterMs = 0): Promise<void> {
  validateId(id);
  // A throttled update still in the queue would land after this and put the
  // activity back on screen, showing a stale in-progress state that nothing
  // will ever clear.
  clearPending(id);
  return Native.end(id, dismissAfterMs).catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.UPDATE_FAILED);
  });
}

/** Ids of every live update this app currently has on screen. */
export function getRunning(): Promise<string[]> {
  return Native.getRunning().catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.NOT_SUPPORTED);
  });
}

/** End every live update this app started. */
export function endAll(): Promise<void> {
  pending.forEach((_, id) => clearPending(id));
  return Native.endAll().catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.UPDATE_FAILED);
  });
}

/**
 * iOS only: the per-activity APNs push token, delivered as ActivityKit issues
 * and rotates it.
 *
 * Send it to your server and address updates to it with
 * `apns-push-type: liveactivity`. It is not the device token and it changes —
 * always send the latest, and stop sending when the activity ends.
 *
 * On Android this never fires: a live update there is a local notification, so
 * a push simply updates it through the normal path.
 */
export function addPushTokenListener(
  listener: (event: PushTokenEvent) => void,
): EmitterSubscription {
  if (!emitter) return noopSubscription;
  // NativeEventEmitter types its payload as `Object`, which is assignable to
  // almost nothing — narrowing here keeps the cast in one place instead of at
  // every call site.
  return emitter.addListener('LiveUpdatePushToken', (event) =>
    listener(event as PushTokenEvent),
  );
}

// ─── Throttling ──────────────────────────────────────────────────────────────

interface PendingUpdate {
  timer: ReturnType<typeof setTimeout>;
  content: LiveUpdateContent;
  /** Everyone waiting on a call that was coalesced into this one. */
  waiters: Array<{ resolve: () => void; reject: (e: unknown) => void }>;
}

const lastSentAt = new Map<string, number>();
const pending = new Map<string, PendingUpdate>();

function send(id: string, content: LiveUpdateContent): Promise<void> {
  lastSentAt.set(id, Date.now());
  return Native.update(id, content).catch((error) => {
    throw toLiveUpdateError(error, LiveUpdateErrorCode.UPDATE_FAILED);
  });
}

/**
 * Trailing-edge throttle: send now if the window has passed, otherwise hold
 * the newest value and send that when it does.
 *
 * Trailing rather than leading because the last value is the true one. A
 * dropped intermediate frame costs nothing — an activity that settles on a
 * stale percentage because the final update arrived a moment too early is the
 * bug this exists to avoid.
 */
function throttled(
  id: string,
  content: LiveUpdateContent,
  throttleMs: number,
): Promise<void> {
  const elapsed = Date.now() - (lastSentAt.get(id) ?? 0);
  const existing = pending.get(id);

  if (!existing && elapsed >= throttleMs) return send(id, content);

  if (existing) {
    // Supersede the queued value; its waiters ride along with this one, since
    // what they asked for is satisfied by anything newer landing.
    existing.content = content;
    return new Promise<void>((resolve, reject) => {
      existing.waiters.push({ resolve, reject });
    });
  }

  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      const queued = pending.get(id);
      pending.delete(id);
      if (!queued) return;
      send(id, queued.content).then(
        () => queued.waiters.forEach((w) => w.resolve()),
        (error) => queued.waiters.forEach((w) => w.reject(error)),
      );
    }, throttleMs - elapsed);

    pending.set(id, {
      timer,
      content,
      waiters: [{ resolve, reject }],
    });
  });
}

/**
 * Drop a queued update. Its waiters resolve rather than reject: the activity
 * has moved on to a state the caller asked for more recently, so nothing has
 * actually gone wrong.
 */
function clearPending(id: string): void {
  const queued = pending.get(id);
  if (!queued) return;
  clearTimeout(queued.timer);
  pending.delete(id);
  queued.waiters.forEach((w) => w.resolve());
}

/**
 * Test seam. Exported because the throttle state is module-level and a test
 * file cannot otherwise reset it between cases; the `__` marks it as not part
 * of the supported API.
 */
export function __resetThrottleState(): void {
  pending.forEach((queued) => clearTimeout(queued.timer));
  pending.clear();
  lastSentAt.clear();
}

export default {
  isSupported,
  getCapabilities,
  configureNotifications,
  start,
  update,
  end,
  endAll,
  getRunning,
  addPushTokenListener,
};

/**
 * Every failure this package produces, as a code you can branch on.
 *
 * Native rejections carry the same strings, so a `catch` looks the same
 * whether validation stopped the call in JS or ActivityKit refused it three
 * layers down.
 */
export const LiveUpdateErrorCode = {
  /** The OS is too old, or the build has no ActivityKit. Nothing to retry. */
  NOT_SUPPORTED: 'NOT_SUPPORTED',
  /**
   * The user switched it off — Live Activities in iOS Settings, or the
   * notification permission on Android. Also nothing to retry, but worth
   * telling the user about, because they can undo it.
   */
  PERMISSION_DENIED: 'PERMISSION_DENIED',
  /** No live update with that id. It ended, or it was never started. */
  NOT_FOUND: 'NOT_FOUND',
  /** The content failed validation. `message` says which field and why. */
  INVALID_CONTENT: 'INVALID_CONTENT',
  /** The native module is missing: not rebuilt after install, or Expo Go. */
  NATIVE_MODULE_UNAVAILABLE: 'NATIVE_MODULE_UNAVAILABLE',
  /** The system accepted the call and then failed it. Rare; see `message`. */
  START_FAILED: 'START_FAILED',
  UPDATE_FAILED: 'UPDATE_FAILED',
} as const;

export type LiveUpdateErrorCode =
  (typeof LiveUpdateErrorCode)[keyof typeof LiveUpdateErrorCode];

export class LiveUpdateError extends Error {
  readonly code: LiveUpdateErrorCode;

  constructor(code: LiveUpdateErrorCode, message: string) {
    super(message);
    this.name = 'LiveUpdateError';
    this.code = code;
    // Extending a built-in through the TS `target: esnext` output keeps the
    // prototype chain, but a consumer transpiling this to ES5 loses it and
    // `instanceof LiveUpdateError` starts returning false. Cheap insurance.
    Object.setPrototypeOf(this, LiveUpdateError.prototype);
  }
}

/**
 * Turn whatever the bridge threw into a LiveUpdateError.
 *
 * React Native rejects with an Error carrying `code` from the native
 * `promise.reject(code, message)`. Anything else — a module that is not there,
 * a serialization failure — arrives shapeless, and takes `fallback`: the
 * caller knows which operation it was, and guessing a code here would put a
 * confident-looking `START_FAILED` on a failed `end()`.
 */
export function toLiveUpdateError(
  error: unknown,
  fallback: LiveUpdateErrorCode = LiveUpdateErrorCode.START_FAILED,
): LiveUpdateError {
  if (error instanceof LiveUpdateError) return error;

  const code = (error as { code?: unknown } | null)?.code;
  const message =
    (error as { message?: unknown } | null)?.message ?? String(error);

  if (typeof code === 'string' && code in LiveUpdateErrorCode) {
    return new LiveUpdateError(code as LiveUpdateErrorCode, String(message));
  }
  return new LiveUpdateError(fallback, String(message));
}

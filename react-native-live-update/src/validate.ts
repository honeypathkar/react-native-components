import { LiveUpdateError, LiveUpdateErrorCode } from './errors';
import type { LiveUpdateContent, StartOptions } from './types';

/**
 * Checked in JS, before anything crosses the bridge.
 *
 * Not defensiveness for its own sake: the failure modes on the other side are
 * genuinely bad to debug. A malformed `ContentState` is dropped by ActivityKit
 * with nothing logged, and a notification with a zero-length progress track
 * posts happily and renders as an empty bar. Both look like "the library is
 * broken" from JS. A thrown error naming the field does not.
 */

/** Long enough for any real headline; short enough that the OS will draw it. */
const MAX_TITLE = 200;
const MAX_MESSAGE = 500;
/** Android truncates the status-bar chip near 7 characters. Allow slack. */
const MAX_STATUS = 40;
const MAX_ID = 128;
/**
 * Android renders every segment as a visible section of the track. Past a
 * dozen they are a few pixels each and the track reads as a solid bar, so the
 * cap is about the output being legible rather than about any API limit.
 */
const MAX_STAGES = 12;

const HEX_COLOR = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
/** `scheme://...` or `scheme:...` — anything Linking could actually open. */
const URL_WITH_SCHEME = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

function fail(message: string): never {
  throw new LiveUpdateError(LiveUpdateErrorCode.INVALID_CONTENT, message);
}

function assertText(
  value: unknown,
  field: string,
  max: number,
  required: boolean,
): void {
  if (value === undefined || value === null) {
    if (required) fail(`${field} is required`);
    return;
  }
  if (typeof value !== 'string') fail(`${field} must be a string`);
  if (required && value.trim() === '') fail(`${field} must not be empty`);
  if (value.length > max) {
    fail(`${field} must be ${max} characters or fewer (got ${value.length})`);
  }
}

function assertColor(value: unknown, field: string): void {
  if (value === undefined || value === null) return;
  if (typeof value !== 'string' || !HEX_COLOR.test(value)) {
    fail(`${field} must be a hex colour like "#C95942" (got ${String(value)})`);
  }
}

export function validateId(id: unknown): void {
  assertText(id, 'id', MAX_ID, true);
}

export function validateContent(content: unknown): void {
  if (typeof content !== 'object' || content === null) {
    fail('content must be an object');
  }
  const c = content as LiveUpdateContent;

  assertText(c.title, 'content.title', MAX_TITLE, true);
  assertText(c.message, 'content.message', MAX_MESSAGE, false);
  assertText(c.status, 'content.status', MAX_STATUS, false);
  assertColor(c.color, 'content.color');

  if (c.progress !== undefined && c.progress !== null) {
    if (typeof c.progress !== 'number' || !Number.isFinite(c.progress)) {
      fail('content.progress must be a number');
    }
    if (c.progress < 0 || c.progress > 1) {
      // A percentage rather than a fraction is the mistake people actually
      // make, and it silently pins the bar at 100%. Say so.
      fail(
        `content.progress is a fraction between 0 and 1, not a percentage (got ${c.progress})`,
      );
    }
  }

  if (c.endsAt !== undefined && c.endsAt !== null) {
    if (typeof c.endsAt !== 'number' || !Number.isFinite(c.endsAt)) {
      fail('content.endsAt must be a number');
    }
    if (c.endsAt <= 0) {
      fail('content.endsAt must be epoch milliseconds');
    }
  }

  if (c.deepLink !== undefined && c.deepLink !== null) {
    if (typeof c.deepLink !== 'string' || !URL_WITH_SCHEME.test(c.deepLink)) {
      fail(
        `content.deepLink must include a scheme, like "myapp://orders/1" (got ${String(c.deepLink)})`,
      );
    }
  }

  if (c.stages !== undefined && c.stages !== null) {
    validateStages(c.stages);
  }

  if (c.actions !== undefined && c.actions !== null) {
    validateActions(c.actions);
  }
}

/** Android shows three and drops the rest, so an over-long list is a bug. */
const MAX_ACTIONS = 3;

function validateActions(actions: unknown): void {
  if (!Array.isArray(actions)) fail('content.actions must be an array');
  if (actions.length > MAX_ACTIONS) {
    fail(
      `content.actions must have ${MAX_ACTIONS} or fewer entries — Android shows no more than that`,
    );
  }

  const seen = new Set<string>();
  actions.forEach((action, i) => {
    if (typeof action !== 'object' || action === null) {
      fail(`content.actions[${i}] must be an object`);
    }
    assertText(action.id, `content.actions[${i}].id`, MAX_ID, true);
    assertText(action.title, `content.actions[${i}].title`, MAX_STATUS, true);

    if (seen.has(action.id)) {
      fail(`content.actions[${i}].id "${action.id}" is used more than once`);
    }
    seen.add(action.id);

    if (action.deepLink !== undefined && action.deepLink !== null) {
      if (
        typeof action.deepLink !== 'string' ||
        !URL_WITH_SCHEME.test(action.deepLink)
      ) {
        fail(
          `content.actions[${i}].deepLink must include a scheme, like "myapp://orders/1"`,
        );
      }
    }
  });
}

function validateStages(stages: unknown): void {
  if (!Array.isArray(stages)) fail('content.stages must be an array');
  if (stages.length === 0) {
    // An empty array is not a plain bar, it is a track with nothing in it —
    // and it renders as one. Omitting the key is what the caller meant.
    fail('content.stages must not be empty — omit it for a plain progress bar');
  }
  if (stages.length > MAX_STAGES) {
    fail(`content.stages must have ${MAX_STAGES} or fewer entries`);
  }

  const seen = new Set<string>();
  stages.forEach((stage, i) => {
    if (typeof stage !== 'object' || stage === null) {
      fail(`content.stages[${i}] must be an object`);
    }
    assertText(stage.id, `content.stages[${i}].id`, MAX_ID, true);
    assertText(stage.title, `content.stages[${i}].title`, MAX_TITLE, true);
    assertColor(stage.color, `content.stages[${i}].color`);

    if (seen.has(stage.id)) {
      fail(`content.stages[${i}].id "${stage.id}" is used more than once`);
    }
    seen.add(stage.id);

    if (
      stage.milestone !== undefined &&
      stage.milestone !== null &&
      typeof stage.milestone !== 'boolean'
    ) {
      fail(`content.stages[${i}].milestone must be a boolean`);
    }

    if (stage.weight !== undefined && stage.weight !== null) {
      if (typeof stage.weight !== 'number' || !Number.isFinite(stage.weight)) {
        fail(`content.stages[${i}].weight must be a number`);
      }
      if (stage.weight <= 0) {
        // Zero would divide the track by nothing on Android.
        fail(`content.stages[${i}].weight must be greater than 0`);
      }
    }
  });
}

export function validateStartOptions(options: unknown): void {
  if (typeof options !== 'object' || options === null) {
    fail('start() takes an object');
  }
  const o = options as StartOptions;
  validateId(o.id);
  assertText(o.name, 'name', MAX_TITLE, true);
  validateContent(o.content);
}

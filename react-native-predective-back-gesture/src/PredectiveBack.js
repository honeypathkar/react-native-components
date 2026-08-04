// src/native/PredictiveBack.js — New File (JS Bridge + Ownership Model)

// Create at src/native/PredictiveBack.js (adapt path to your project).



import { NativeEventEmitter, NativeModules, Platform } from 'react-native';

const PredictiveBackModule =
  Platform.OS === 'android' ? NativeModules.PredictiveBackModule : null;

/** True when the native back-event bridge is present (Android only). */
export const PREDICTIVE_BACK_SUPPORTED = PredictiveBackModule != null;

/**
 * True when the OS also reports live gesture progress (Android 14 / API 34+).
 * Below that only the commit arrives — callers should play a timed animation.
 */
export const PREDICTIVE_BACK_HAS_PROGRESS =
  PREDICTIVE_BACK_SUPPORTED && PredictiveBackModule.progressAvailable === true;

export const EDGE_LEFT  = 0;
export const EDGE_RIGHT = 1;

/** Back is React Native's to handle (BackHandler + React Navigation). */
export const BACK_MODE_DEFAULT = 'default';
/**
 * Nothing in the app claims back → Android plays its own back-to-home animation.
 * Set this at the root of the stack (nothing to go back to).
 */
export const BACK_MODE_SYSTEM  = 'system';

let owner       = null;
let handlers    = null;
let fallbackMode = BACK_MODE_DEFAULT;
let appliedMode  = null;
let subscriptions = null;

const apply = () => {
  const mode = owner ? 'app' : fallbackMode;
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
    emitter.addListener('predictiveBackStart',    e => dispatch('onStart',    e)),
    emitter.addListener('predictiveBackProgress', e => dispatch('onProgress', e)),
    emitter.addListener('predictiveBackCancel',   () => dispatch('onCancel')),
    emitter.addListener('predictiveBackCommit',   () => dispatch('onCommit')),
  ];
};

/**
 * What happens when no screen has claimed back.
 * Call from navigator on state change.
 */
export const setFallbackBackMode = mode => {
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
  appliedMode = null;   // force re-apply even if mode string unchanged
  apply();
};

/** Give back control, but only if `token` still holds it. */
export const releasePredictiveBack = token => {
  if (!PredictiveBackModule || owner !== token) return;
  owner = null;
  handlers = null;
  apply();
};


import {
  NativeEventEmitter,
  NativeModules,
  Platform,
  type EmitterSubscription,
} from 'react-native';
import type {
  LiveUpdateCapabilities,
  LiveUpdateContent,
  LiveUpdateHandle,
  LiveUpdateSupport,
  NotificationConfig,
} from './types';

const LINKING_ERROR =
  `The package 'react-native-live-update' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

/** The native surface, exactly as the Kotlin and Swift modules expose it. */
interface NativeLiveUpdate {
  isSupported(): Promise<LiveUpdateSupport>;
  getCapabilities(): Promise<LiveUpdateCapabilities>;
  configureNotifications(config: NotificationConfig): Promise<void>;
  start(
    id: string,
    name: string,
    content: LiveUpdateContent,
    options: { persistent: boolean },
  ): Promise<LiveUpdateHandle>;
  update(id: string, content: LiveUpdateContent): Promise<void>;
  end(id: string, dismissAfterMs: number): Promise<void>;
  getRunning(): Promise<string[]>;
  endAll(): Promise<void>;
}

const linked = NativeModules.LiveUpdate as NativeLiveUpdate | undefined;

/**
 * Throwing on first *use* rather than on import.
 *
 * A module that threw at import time would take down any screen that so much
 * as references the package — including the one whose job is to tell the user
 * live updates are unavailable here.
 */
export const Native: NativeLiveUpdate =
  linked ??
  (new Proxy(
    {},
    {
      get() {
        throw new Error(LINKING_ERROR);
      },
    },
  ) as NativeLiveUpdate);

export const isLinked = linked !== undefined;

/**
 * Events come from ActivityKit only, so there is nothing to attach to on
 * Android — and constructing a NativeEventEmitter over a module with no
 * add/removeListeners is itself a warning on newer React Native.
 */
export const emitter =
  Platform.OS === 'ios' && linked
    ? new NativeEventEmitter(NativeModules.LiveUpdate)
    : null;

export const noopSubscription = { remove() {} } as EmitterSubscription;

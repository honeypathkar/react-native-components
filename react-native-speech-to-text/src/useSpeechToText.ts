import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { EmitterSubscription } from 'react-native';
import SpeechToText from './index';
import type {
  LocaleInfo,
  PermissionStatus,
  SpeechEndReason,
  SpeechErrorEvent,
  StartListeningOptions,
} from './types';

export interface UseSpeechToTextOptions extends StartListeningOptions {
  /**
   * Load the device's supported locales when the hook mounts. Default `false` —
   * it is a native round-trip you rarely need on every screen.
   */
  loadLanguagesOnMount?: boolean;
  /** Fired with the committed transcript each time a session ends with speech. */
  onResult?: (transcript: string) => void;
  /** Fired when a session ends, for any reason. */
  onEnd?: (reason: SpeechEndReason, transcript: string) => void;
  /** Fired on recognition or audio errors. */
  onError?: (error: SpeechErrorEvent) => void;
}

export interface UseSpeechToTextResult {
  /** `true` between `startListening()` and the session ending. */
  isListening: boolean;
  /** The last committed (final) transcript. */
  transcript: string;
  /** The live, still-changing transcript. Cleared when a session ends. */
  interimTranscript: string;
  /** Normalized mic level in `0..1`, for meters and waveforms. */
  audioLevel: number;
  /** The most recent error, or `null`. */
  error: SpeechErrorEvent | null;
  /** Locales the device can transcribe. Populated by `loadLanguages()`. */
  availableLanguages: LocaleInfo[];
  startListening: (overrides?: StartListeningOptions) => Promise<void>;
  stopListening: () => Promise<string>;
  cancel: () => Promise<void>;
  reset: () => void;
  loadLanguages: () => Promise<LocaleInfo[]>;
  requestPermissions: () => Promise<PermissionStatus>;
}

/**
 * React binding over the native module. Owns the event subscriptions and tears
 * them down (plus any live session) on unmount.
 */
export function useSpeechToText(
  options: UseSpeechToTextOptions = {}
): UseSpeechToTextResult {
  const {
    loadLanguagesOnMount = false,
    onResult,
    onEnd,
    onError,
    ...startOptions
  } = options;

  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [interimTranscript, setInterimTranscript] = useState('');
  const [audioLevel, setAudioLevel] = useState(0);
  const [error, setError] = useState<SpeechErrorEvent | null>(null);
  const [availableLanguages, setAvailableLanguages] = useState<LocaleInfo[]>([]);

  const isMounted = useRef(true);
  const listeningRef = useRef(false);

  // Keep callbacks and options in refs so the event subscriptions below are set
  // up exactly once instead of resubscribing on every render.
  const callbacksRef = useRef({ onResult, onEnd, onError });
  callbacksRef.current = { onResult, onEnd, onError };

  const startOptionsRef = useRef(startOptions);
  startOptionsRef.current = startOptions;

  useEffect(() => {
    isMounted.current = true;

    const subscriptions: EmitterSubscription[] = [
      SpeechToText.on('onSpeechPartialResults', ({ transcript: text }) => {
        if (!isMounted.current) return;
        setInterimTranscript(text);
      }),

      SpeechToText.on('onSpeechResults', ({ transcript: text }) => {
        if (!isMounted.current) return;
        setTranscript(text);
        setInterimTranscript('');
        if (text) callbacksRef.current.onResult?.(text);
      }),

      SpeechToText.on('onSpeechEnd', ({ reason, transcript: text }) => {
        listeningRef.current = false;
        if (!isMounted.current) return;
        setIsListening(false);
        setInterimTranscript('');
        setAudioLevel(0);
        callbacksRef.current.onEnd?.(reason, text);
      }),

      SpeechToText.on('onSpeechError', (event) => {
        if (!isMounted.current) return;
        setError(event);
        callbacksRef.current.onError?.(event);
      }),

      SpeechToText.on('onSpeechVolumeChanged', ({ value }) => {
        if (!isMounted.current) return;
        setAudioLevel(value);
      }),
    ];

    return () => {
      isMounted.current = false;
      subscriptions.forEach((subscription) => subscription.remove());
      if (listeningRef.current) {
        listeningRef.current = false;
        SpeechToText.destroy().catch(() => {});
      }
    };
  }, []);

  const loadLanguages = useCallback(async (): Promise<LocaleInfo[]> => {
    const locales = await SpeechToText.getAvailableLocales();
    if (isMounted.current) setAvailableLanguages(locales);
    return locales;
  }, []);

  useEffect(() => {
    if (loadLanguagesOnMount) {
      loadLanguages().catch(() => {});
    }
  }, [loadLanguagesOnMount, loadLanguages]);

  const startListening = useCallback(
    async (overrides: StartListeningOptions = {}) => {
      if (listeningRef.current) return;

      setError(null);
      setInterimTranscript('');

      try {
        listeningRef.current = true;
        setIsListening(true);
        await SpeechToText.startListening({
          ...startOptionsRef.current,
          ...overrides,
        });
      } catch (e: any) {
        listeningRef.current = false;
        if (isMounted.current) setIsListening(false);
        const failure: SpeechErrorEvent = {
          code: e?.code ?? 'start_failed',
          message: e?.message ?? String(e),
        };
        if (isMounted.current) setError(failure);
        callbacksRef.current.onError?.(failure);
      }
    },
    []
  );

  const stopListening = useCallback(async (): Promise<string> => {
    if (!listeningRef.current) return '';
    const result = await SpeechToText.stopListening();
    return result.transcript;
  }, []);

  const cancel = useCallback(async () => {
    if (!listeningRef.current) return;
    await SpeechToText.cancel();
  }, []);

  const reset = useCallback(() => {
    setTranscript('');
    setInterimTranscript('');
    setError(null);
    setAudioLevel(0);
  }, []);

  const requestPermissions = useCallback(
    () => SpeechToText.requestPermissions(),
    []
  );

  return useMemo(
    () => ({
      isListening,
      transcript,
      interimTranscript,
      audioLevel,
      error,
      availableLanguages,
      startListening,
      stopListening,
      cancel,
      reset,
      loadLanguages,
      requestPermissions,
    }),
    [
      isListening,
      transcript,
      interimTranscript,
      audioLevel,
      error,
      availableLanguages,
      startListening,
      stopListening,
      cancel,
      reset,
      loadLanguages,
      requestPermissions,
    ]
  );
}

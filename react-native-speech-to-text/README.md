# @honeypathkar/react-native-speech-to-text

Native speech-to-text for React Native on **iOS and Android**, with configurable
silence detection that commits the transcript and stops the microphone once the
user has paused.

- Real-time interim transcripts (`SFSpeechRecognizer` on iOS, `SpeechRecognizer` on Android)
- Native silence / pause detection with auto-stop (default 2.5s)
- Audio level metering for waveforms and mic meters
- Every recognition locale the device supports, with display names
- Optional offline / on-device recognition
- Promise API, typed event stream, and a `useSpeechToText` hook

## Install

```sh
npm install @honeypathkar/react-native-speech-to-text
cd ios && pod install
```

Android is picked up by autolinking; just rebuild.

### iOS setup

Add both keys to `ios/<YourApp>/Info.plist`:

```xml
<key>NSSpeechRecognitionUsageDescription</key>
<string>Used to transcribe what you say into text.</string>
<key>NSMicrophoneUsageDescription</key>
<string>Used to record your voice for transcription.</string>
```

Minimum deployment target: **iOS 13.0**.

### Android setup

`RECORD_AUDIO` and the `<queries>` entry for `RecognitionService` ship in the
library manifest and merge into your app automatically — no manual edits needed.
`requestPermissions()` handles the runtime prompt.

Minimum SDK: **21**. On-device recognition needs **API 31+**.

## Quick start

```ts
import SpeechToText from '@honeypathkar/react-native-speech-to-text';

const { granted } = await SpeechToText.requestPermissions();
if (!granted) return;

SpeechToText.on('onSpeechPartialResults', ({ transcript }) => {
  console.log('Interim:', transcript);
});

SpeechToText.on('onSpeechResults', ({ transcript }) => {
  console.log('Final:', transcript);
});

SpeechToText.on('onSpeechEnd', ({ reason }) => {
  console.log('Stopped because:', reason); // 'silence' | 'manual' | ...
});

await SpeechToText.startListening({
  locale: 'en-US',
  silenceTimeoutMs: 2500, // auto-stop after a 2.5s pause
  interimResults: true,
});
```

`stopListening()` also resolves with the final transcript, if you would rather
await it than listen for the event:

```ts
const { transcript, reason } = await SpeechToText.stopListening();
```

## Hook

```tsx
import { useSpeechToText } from '@honeypathkar/react-native-speech-to-text';

function VoiceInput() {
  const {
    isListening,
    transcript,
    interimTranscript,
    audioLevel,
    error,
    startListening,
    stopListening,
  } = useSpeechToText({
    locale: 'en-US',
    silenceTimeoutMs: 2500,
    onResult: (text) => console.log('committed:', text),
  });

  return (
    <View>
      <Text>{transcript || interimTranscript || 'Say something…'}</Text>
      <View style={{ width: 200 * audioLevel, height: 4, backgroundColor: '#0a0' }} />
      <Button
        title={isListening ? 'Stop' : 'Start'}
        onPress={isListening ? stopListening : () => startListening()}
      />
      {error ? <Text>{error.message}</Text> : null}
    </View>
  );
}
```

The hook removes its listeners and tears down any live session on unmount.

## Silence detection

`silenceDetectionMode` controls what counts as "still talking":

| Mode | Timer resets on | Trade-off |
| --- | --- | --- |
| `transcript` *(default)* | New words from the recognizer | Immune to background noise |
| `audio` | Input level above threshold | Most responsive; a noisy room can hold the mic open |
| `hybrid` | Either signal | Least likely to cut off a slow speaker; stops latest |

`transcript` is the default because in a noisy room the audio level alone rarely
drops below the threshold, so `audio` and `hybrid` can keep listening forever.

Android's `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` is advisory and
widely ignored by OEM recognition services, so the pause is timed in the library
on both platforms. That keeps `silenceTimeoutMs` meaning the same thing everywhere.

Set `continuous: true` to keep the microphone open through pauses — you still get
an `onSpeechSilence` event on every pause, but the session only ends when you call
`stopListening()`.

## API

### Methods

| Method | Description |
| --- | --- |
| `requestPermissions()` | Prompts for microphone (and speech recognition on iOS). |
| `getPermissionStatus()` | Reads permission state without prompting. |
| `isAvailable()` | Whether recognition works right now. |
| `isAvailableForLocale(locale)` | Whether one BCP-47 locale is usable. |
| `supportsOnDeviceRecognition(locale?)` | Whether offline recognition is available. |
| `getAvailableLocales()` | All supported locales with display names. |
| `getAvailableLanguages()` | All supported locales as BCP-47 tags. |
| `startListening(options?)` | Opens the mic and starts transcribing. |
| `stopListening()` | Stops and resolves with the final transcript. |
| `cancel()` | Stops and discards the transcript. |
| `destroy()` | Tears down and releases the audio hardware. |
| `on(event, listener)` | Subscribes; returns a subscription with `.remove()`. |
| `removeAllListeners(event?)` | Drops listeners. |

### `startListening` options

| Option | Default | Description |
| --- | --- | --- |
| `locale` | device locale | BCP-47 tag, e.g. `'en-US'`, `'hi-IN'`, `'gu-IN'`. |
| `silenceTimeoutMs` | `2500` | Pause length that triggers auto-stop. |
| `interimResults` | `true` | Stream partial transcripts. |
| `continuous` | `false` | Keep listening through pauses. |
| `requiresOnDeviceRecognition` | `false` | Force offline. Rejects if unavailable. |
| `silenceDetectionMode` | `'transcript'` | `transcript` \| `audio` \| `hybrid`. |
| `silenceThresholdDb` | `-35` | iOS silence threshold, dBFS. |
| `androidSilenceThresholdRms` | `2` | Android silence threshold, RMS scale. |
| `noSpeechTimeoutMs` | `0` (off) | Give up if the user never speaks. |
| `maxDurationMs` | `0` (off) | Hard cap on one session. |
| `addsPunctuation` | `true` | iOS 16+ automatic punctuation. |
| `taskHint` | `'unspecified'` | iOS recognition hint. |
| `contextualStrings` | – | Bias toward names/jargon. Android needs API 33+. |
| `volumeUpdates` | `true` | Emit `onSpeechVolumeChanged`. |
| `volumeIntervalMs` | `100` | Throttle for volume events. |

### Events

| Event | Payload |
| --- | --- |
| `onSpeechReady` | `{ locale, silenceTimeoutMs, onDevice }` |
| `onSpeechStart` | `{ timestamp }` — the user actually started speaking |
| `onSpeechPartialResults` | `{ transcript, isFinal: false, segments }` |
| `onSpeechResults` | `{ transcript, isFinal: true }` |
| `onSpeechSilence` | `{ durationMs, transcript }` |
| `onSpeechEnd` | `{ reason, transcript }` |
| `onSpeechError` | `{ code, message, nativeCode? }` |
| `onSpeechVolumeChanged` | `{ value, db }` — `value` is `0..1` |

`reason` is one of `silence`, `manual`, `no_speech`, `max_duration`,
`recognizer_final`, `cancelled`, `destroyed`, `error`, `not_listening`.

### Error codes

`permission_denied`, `already_listening`, `recognizer_unavailable`,
`locale_not_supported`, `on_device_unavailable`, `no_match`,
`no_speech_detected`, `network_error`, `network_timeout`, `recognizer_busy`,
`audio_error`, `recognition_error`.

## Platform differences

| | iOS | Android |
| --- | --- | --- |
| Engine | `SFSpeechRecognizer` + `AVAudioEngine` | `SpeechRecognizer` |
| Word-level `segments` | Yes | Empty array |
| Volume scale (`db`) | dBFS | `onRmsChanged` scale |
| Speech permission | Separate from mic | Mirrors mic |
| Offline | iOS 13+ where supported | API 31+ |
| `continuous` | One session stays open | Recognizer restarts, transcript accumulates |

`value` on `onSpeechVolumeChanged` is normalized to `0..1` on both platforms, so
prefer it over `db` for meters.

## License

MIT

# @honeypathkar/react-native-soundbox-tts

Production-grade multilingual Android TTS soundbox engine for React Native designed for retail environments and payment announcements.

- **`USAGE_MEDIA` Audio Stream**: Speaks over media channel, bypassing phone silent / vibrate switches.
- **Audio Ducking (`TRANSIENT_MAY_DUCK`)**: Automatically lowers background music/media during announcements with delayed focus release.
- **8 Indian Languages Sentence Builder**:
  - English (`en`), Hindi (`hi`), Marathi (`mr`), Bengali (`bn`), Gujarati (`gu`), Tamil (`ta`), Telugu (`te`), Kannada (`kn`).
- **Engine Auto-Recovery**: Reconnects and rebuilds dead TTS instances automatically.
- **`QUEUE_ADD` Audio Queuing**: Prevents dropped speech during simultaneous payment surges.

## Installation

```bash
npm install @honeypathkar/react-native-soundbox-tts
# or
yarn add @honeypathkar/react-native-soundbox-tts
```

## Usage

```typescript
import SoundboxTTS from '@honeypathkar/react-native-soundbox-tts';

// 1. Initialize Engine
await SoundboxTTS.init();

// 2. Announce Payment
SoundboxTTS.speakPayment({
  amountPaise: 50000, // ₹500
  payerName: 'Ramesh',
  appName: 'Google Pay',
  language: 'hi', // Speaks: "Google Pay पर Ramesh से 500 रुपये प्राप्त हुए"
});

// 3. Announce Raw Text
SoundboxTTS.speak('Soundbox is ready');

// 4. Change Language / Check Voice Availability
const isAvailable = await SoundboxTTS.isLanguageAvailable('hi');
if (isAvailable) {
  await SoundboxTTS.setLanguage('hi');
}
```

## License
MIT

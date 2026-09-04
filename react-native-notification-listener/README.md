# @honeypathkar/react-native-notification-listener

Production-ready, general-purpose Android `NotificationListenerService` module for React Native with:
- **All-Notification Capture**: Captures notifications at the Android system level even when the app is closed or backgrounded.
- **7-Slot Text Extraction**: Title, bigText, subText, summary, info, and message lines.
- **Configurable Dynamic Filtering**: Filter by package allowlist, chat messages, ongoing events, summaries, and custom regex rules.
- **Built-in Payment Parser**: Auto-parses UPI/Bank amounts in paise, credit vs debit detection, UTR / UPI reference numbers, and payer names.
- **Multi-Layer Deduplication**: In-memory key hashes, UTR transaction keys, and 90-second fuzzy window.
- **Offline SQLite Persistence**: SQLite database of record for resilient offline storage.

## Installation

```bash
npm install @honeypathkar/react-native-notification-listener
# or
yarn add @honeypathkar/react-native-notification-listener
```

## Setup (Android)

Add the notification listener service declaration in your `android/app/src/main/AndroidManifest.xml` (usually automatically merged, but verify permissions):

```xml
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```

## Usage

```typescript
import NotificationListener from '@honeypathkar/react-native-notification-listener';

// 1. Check & Request Notification Access
const hasPermission = await NotificationListener.isPermissionGranted();
if (!hasPermission) {
  NotificationListener.openPermissionSettings();
}

// 2. Configure Dynamic Filters
NotificationListener.configureFilters({
  packageAllowlist: [
    'com.google.android.apps.nfc.plugin.card.bpay', // GPay
    'com.phonepe.app',                              // PhonePe
    'net.one97.paytm',                              // Paytm
    'com.whatsapp'
  ],
  ignoreSummaries: true,
  ignoreChatMessages: false,
});

// 3. Listen to Notifications
const subNotification = NotificationListener.onNotificationReceived((event) => {
  console.log('Received notification from:', event.packageName);
  console.log('Content:', event.combinedText);
});

// 4. Listen to Auto-Detected Payments
const subPayment = NotificationListener.onPaymentDetected((payment) => {
  console.log('Payment Detected:', payment.amountPaise / 100, 'INR from', payment.payerName);
});

// 5. Query Native Offline SQLite Store
const history = await NotificationListener.getPayments(50, 0);
```

## License
MIT

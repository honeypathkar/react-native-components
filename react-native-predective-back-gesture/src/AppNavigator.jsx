// Add this code inside your file where you have declared all the screens navigation or in the App.jsx

import {
  BACK_MODE_DEFAULT,
  BACK_MODE_SYSTEM,
  setFallbackBackMode,
} from "../native/PredictiveBack";

// Inside the AppNavigator component:
const syncBackMode = React.useCallback(() => {
  setFallbackBackMode(
    navigationRef.isReady() && navigationRef.canGoBack()
      ? BACK_MODE_DEFAULT
      : BACK_MODE_SYSTEM,
  );
}, []);

// On the NavigationContainer:
<NavigationContainer
  ref={navigationRef}
  onReady={syncBackMode}
  onStateChange={syncBackMode}
></NavigationContainer>;

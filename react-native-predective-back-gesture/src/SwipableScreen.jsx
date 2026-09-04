import React, { useCallback, useEffect, useRef } from "react";
import { StyleSheet, Dimensions, View } from "react-native";
import { GestureDetector, Gesture } from "react-native-gesture-handler";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  withSpring,
  runOnJS,
  interpolate,
  Extrapolation,
  Easing,
  cancelAnimation,
} from "react-native-reanimated";
import { useNavigation, useFocusEffect } from "@react-navigation/native";
import {
  PREDICTIVE_BACK_HAS_PROGRESS,
  PREDICTIVE_BACK_SUPPORTED,
  acquirePredictiveBack,
  releasePredictiveBack,
} from "./PredectiveBack";

const { width, height } = Dimensions.get("window");

/**
 * How the screen moves while the gesture is in flight.
 *
 * `card` is Material's predictive back: the screen shrinks and drifts a little,
 * staying on screen so the user can see what they are about to go back to, and
 * only leaves once they commit. `slide` is the older, flatter idea — the screen
 * tracks the finger across the full width and walks off the edge, which is what
 * most stock apps still do.
 *
 * The default stays `card`, so nothing changes for anything already using this.
 */
export const ANIMATION_CARD = "card";
export const ANIMATION_SLIDE = "slide";

// Material predictive-back peek: the card shrinks and drifts a little way to the right
// while the gesture is in flight, revealing the screen underneath. It only leaves the
// screen once the user commits.
const MAX_SCALE_DOWN = 0.13;
const MAX_PEEK_X = width * 0.12;
const MAX_PEEK_Y = height * 0.03;
const CORNER_RADIUS = 32;
const MAX_DIM = 0.5;

/**
 * How far a full drag carries a sliding screen, as a fraction of the width.
 *
 * Not 1. Dragging the screen all the way off under the finger leaves nothing on
 * screen before the gesture is even released, so a cancel has to haul the whole
 * width back and a commit has nothing left to animate — the transition is over
 * before the decision is made. Stopping the drag short keeps a piece of the
 * outgoing screen in view the whole time and leaves the commit something to do,
 * which is what makes the release read as a release.
 */
const SLIDE_PEEK_TRAVEL = 0.6;

// Screens enter from the right and leave back to the right — the exit always mirrors
// the entry, whichever edge the gesture came from.
const ENTER_DURATION = 280;
const EXIT_DURATION = 240;
const EASING = Easing.out(Easing.bezierFn(0.25, 0.46, 0.45, 0.94));
const SPRING = { damping: 28, stiffness: 260, mass: 0.85 };

// Drag gesture: how far the finger travels for a full peek, and what commits it.
const EDGE_WIDTH = 60;
// Keeps the strip clear of the header, so the back button stays tappable.
const HEADER_INSET = 75;
const DRAG_RANGE = width * 0.6;
const PEEK_THRESHOLD = 0.35;
const VELOCITY_THRESHOLD = 900;
const MIN_VELOCITY_DISTANCE = 50;

const SwipeableScreen = ({
  children,
  enabled = true,
  style,
  onHaptic,
  backgroundColor = '#000000',
  onGoBack,
  animation = ANIMATION_CARD,
  peekTravel = SLIDE_PEEK_TRAVEL,
}) => {
  const isSlide = animation === ANIMATION_SLIDE;

  // Clamped rather than trusted: a value above 1 would push the screen past the
  // edge mid-drag and then have the commit animate it back inwards.
  const slideTravel = Math.min(Math.max(peekTravel, 0), 1);

  // A peek only has to travel far enough to read as a peek, so the card
  // completes in well under a screen width. A slide is the screen itself under
  // the finger, and anything other than one-to-one feels like lag.
  const dragRange = isSlide ? width : DRAG_RANGE;

  let navigation = null;
  try {
    navigation = useNavigation();
  } catch (e) {
    // Component rendered outside React Navigation container
  }

  // Gesture progress (0…1) — drives the peek: shrink, drift right, rounded corners.
  const peek = useSharedValue(0);
  // Dismissal progress (0…1) — slides the card clear to the right. Also runs in
  // reverse on mount so the entry mirrors the exit.
  const exit = useSharedValue(1);
  // -1…1, how far the touch sits above/below centre; tilts the peek vertically.
  const pivot = useSharedValue(0);
  // Set while the OS gesture owns the animation, so the pan cannot drive it too.
  const nativeActive = useSharedValue(0);

  // Guards against a second back event landing while the exit animation is running.
  const isDismissing = useRef(false);

  // ─── Mount: slide in from the right ─────────────────────────────────────
  useEffect(() => {
    exit.value = withTiming(0, { duration: ENTER_DURATION, easing: EASING });
  }, [exit]);

  // ─── JS-thread helpers ───────────────────────────────────────────────────
  const fireHaptic = useCallback(() => {
    try {
      if (onHaptic) onHaptic();
    } catch (e) {}
  }, [onHaptic]);

  const settle = useCallback(() => {
    // Put the card back at rest — used when a pop is refused or cancelled.
    isDismissing.current = false;
    nativeActive.value = 0;
    peek.value = withTiming(0, { duration: 160, easing: EASING });
    exit.value = withTiming(0, { duration: 200, easing: EASING });
  }, [peek, exit, nativeActive]);

  const goBack = useCallback(() => {
    if (onGoBack) {
      onGoBack();
      return;
    }
    if (!navigation || !navigation.canGoBack()) {
      settle();
      return;
    }
    navigation.goBack();
    // goBack dispatches through `beforeRemove`, so it may not actually pop.
    requestAnimationFrame(() => {
      if (navigation && navigation.isFocused && navigation.isFocused()) {
        settle();
      }
    });
  }, [navigation, settle, onGoBack]);

  // ─── Commit / cancel ─────────────────────────────────────────────────────
  const commit = useCallback(
    (duration = EXIT_DURATION) => {
      if (isDismissing.current) {
        return;
      }
      isDismissing.current = true;
      fireHaptic();
      cancelAnimation(exit);
      exit.value = withTiming(1, { duration, easing: EASING }, (finished) => {
        "worklet";
        if (finished) {
          runOnJS(goBack)();
        }
      });
    },
    [exit, fireHaptic, goBack],
  );

  const cancel = useCallback(() => {
    nativeActive.value = 0;
    cancelAnimation(peek);
    peek.value = withSpring(0, SPRING);
  }, [peek, nativeActive]);

  // ─── System back gesture (Android) ───────────────────────────────────────
  const token = useRef({}).current;
  const canGoBackInNav = navigation && navigation.canGoBack ? navigation.canGoBack() : false;
  const isActive = enabled && (canGoBackInNav || !!onGoBack);

  const focusEffectCallback = useCallback(() => {
    if (!PREDICTIVE_BACK_SUPPORTED || !isActive) {
      return undefined;
    }

    const track = (event) => {
      peek.value = event.progress;
      pivot.value = (event.touchY / height) * 2 - 1;
    };

    acquirePredictiveBack(token, {
      onStart: (event) => {
        if (isDismissing.current) {
          return;
        }
        nativeActive.value = 1;
        cancelAnimation(peek);
        track(event);
      },
      onProgress: (event) => {
        if (!isDismissing.current) {
          track(event);
        }
      },
      onCancel: cancel,
      // Without live progress (pre-Android 14, or a 3-button back press) the card
      // has not moved yet, so give the slide-out a little more room to breathe.
      onCommit: () =>
        commit(PREDICTIVE_BACK_HAS_PROGRESS ? EXIT_DURATION : ENTER_DURATION),
    });

    return () => releasePredictiveBack(token);
  }, [token, isActive, peek, pivot, nativeActive, cancel, commit]);

  if (navigation) {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    useFocusEffect(focusEffectCallback);
  } else {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    useEffect(focusEffectCallback, [focusEffectCallback]);
  }

  // ─── Drag-to-close from the left edge ────────────────────────────────────
  // On Android this covers the strip just inside the system gesture zone; on iOS it
  // is the only way back. Both drive the same peek, so the card looks identical
  // however the gesture arrived.
  const panGesture = Gesture.Pan()
    .enabled(isActive)
    .hitSlop({ left: 0, width: EDGE_WIDTH, top: -HEADER_INSET })
    .activeOffsetX([10, 500])
    .failOffsetY([-18, 18])
    .onBegin(() => {
      "worklet";
      if (nativeActive.value) {
        return;
      }
      cancelAnimation(peek);
    })
    .onUpdate((event) => {
      "worklet";
      if (nativeActive.value || event.translationX <= 0) {
        return;
      }
      peek.value = Math.min(event.translationX / dragRange, 1);
      pivot.value = (event.y / height) * 2 - 1;
    })
    .onEnd((event) => {
      "worklet";
      if (nativeActive.value) {
        return;
      }
      const { translationX, velocityX } = event;
      const shouldCommit =
        peek.value > PEEK_THRESHOLD ||
        (velocityX > VELOCITY_THRESHOLD &&
          translationX > MIN_VELOCITY_DISTANCE);

      if (shouldCommit) {
        runOnJS(commit)(velocityX > VELOCITY_THRESHOLD ? 160 : EXIT_DURATION);
      } else {
        peek.value = withSpring(0, SPRING);
      }
    });

  // ─── Card ────────────────────────────────────────────────────────────────
  const screenStyle = useAnimatedStyle(() => {
    const p = peek.value;
    const e = exit.value;

    if (isSlide) {
      // One expression for both halves of the journey. The gesture carries the
      // screen as far as slideTravel allows; the commit takes it from wherever
      // that left off out to the full width. Written as a single blend so there
      // is no jump at the hand-off — a release at 40% of the drag continues
      // from exactly where the finger left it, rather than snapping to a fresh
      // starting point.
      const travelled = p * slideTravel * width;
      return {
        transform: [{ translateX: travelled + (width - travelled) * e }],
      };
    }

    return {
      transform: [
        { translateX: p * MAX_PEEK_X + e * width },
        { translateY: pivot.value * p * MAX_PEEK_Y },
        { scale: 1 - MAX_SCALE_DOWN * p },
      ],
      borderRadius: interpolate(
        p,
        [0, 0.05, 1],
        [0, CORNER_RADIUS, CORNER_RADIUS],
        Extrapolation.CLAMP,
      ),
      overflow: "hidden",
    };
  });

  const backdropStyle = useAnimatedStyle(() => {
    const p = peek.value;
    const e = exit.value;

    if (e > 0) {
      return { opacity: 0 };
    }

    return {
      opacity: interpolate(p, [0, 1], [MAX_DIM, 0], Extrapolation.CLAMP),
    };
  });

  return (
    <View style={styles.outerWrapper}>
      <Animated.View
        pointerEvents="none"
        style={[styles.backdrop, backdropStyle]}
      />

      <GestureDetector gesture={panGesture}>
        <Animated.View
          style={[
            styles.screen,
            { backgroundColor },
            screenStyle,
            style,
          ]}
        >
          {children}
        </Animated.View>
      </GestureDetector>
    </View>
  );
};

export default SwipeableScreen;

const styles = StyleSheet.create({
  outerWrapper: {
    flex: 1,
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    // backgroundColor: '#000000',
    opacity: 0,
    zIndex: 0,
  },
  screen: {
    flex: 1,
    zIndex: 1,
  },
});

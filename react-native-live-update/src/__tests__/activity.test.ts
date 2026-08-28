// Replaces the module that reaches for NativeModules, so none of this needs a
// React Native runtime. The factory has to build the mocks inline: jest hoists
// it above every `const` in the file, so it cannot close over one.
jest.mock('../native', () => ({
  Native: {
    isSupported: jest.fn(),
    getCapabilities: jest.fn(),
    configureNotifications: jest.fn(),
    start: jest.fn(),
    update: jest.fn(),
    end: jest.fn(),
    getRunning: jest.fn(),
    endAll: jest.fn(),
  },
  isLinked: true,
  emitter: null,
  noopSubscription: { remove: () => {} },
}));

import { Native } from '../native';

const mockNative = Native as jest.Mocked<typeof Native>;

import {
  LiveUpdateError,
  LiveUpdateErrorCode,
  __resetThrottleState,
  end,
  getCapabilities,
  isSupported,
  start,
  update,
} from '../index';

beforeEach(() => {
  jest.clearAllMocks();
  __resetThrottleState();
  mockNative.start.mockResolvedValue({ id: 'order-1' });
  mockNative.update.mockResolvedValue(undefined);
  mockNative.end.mockResolvedValue(undefined);
});

const content = { title: 'Order #1', progress: 0.5 };

describe('start', () => {
  it('passes the id, name and content straight through', async () => {
    await start({ id: 'order-1', name: 'Order #1', content });
    expect(mockNative.start).toHaveBeenCalledWith('order-1', 'Order #1', content, {
      persistent: false,
    });
  });

  it('validates before touching the bridge', async () => {
    await expect(
      start({ id: '', name: 'Order #1', content }),
    ).rejects.toBeInstanceOf(LiveUpdateError);
    expect(mockNative.start).not.toHaveBeenCalled();
  });
});

describe('persistence', () => {
  it('defaults to off', async () => {
    await start({ id: 'order-1', name: 'Order #1', content });
    expect(mockNative.start).toHaveBeenCalledWith(
      'order-1',
      'Order #1',
      content,
      { persistent: false },
    );
  });

  it('passes the flag through when asked for', async () => {
    await start({ id: 'order-1', name: 'Order #1', content, persistent: true });
    expect(mockNative.start).toHaveBeenCalledWith(
      'order-1',
      'Order #1',
      content,
      { persistent: true },
    );
  });
});

describe('error mapping', () => {
  it('preserves a native code', async () => {
    mockNative.start.mockRejectedValue(
      Object.assign(new Error('Notifications are turned off for this app'), {
        code: 'PERMISSION_DENIED',
      }),
    );

    await expect(
      start({ id: 'order-1', name: 'Order #1', content }),
    ).rejects.toMatchObject({
      code: LiveUpdateErrorCode.PERMISSION_DENIED,
      message: 'Notifications are turned off for this app',
    });
  });

  it('falls back to the code for the operation, not a guess', async () => {
    mockNative.end.mockRejectedValue(new Error('boom'));
    await expect(end('order-1')).rejects.toMatchObject({
      code: LiveUpdateErrorCode.UPDATE_FAILED,
    });
  });

  it('never rejects from isSupported, even unlinked', async () => {
    mockNative.isSupported.mockRejectedValue(new Error('not linked'));
    await expect(isSupported()).resolves.toEqual({
      supported: false,
      enabled: false,
      reason: 'not linked',
    });
  });

  it('does reject from getCapabilities', async () => {
    mockNative.getCapabilities.mockRejectedValue(new Error('not linked'));
    await expect(getCapabilities()).rejects.toMatchObject({
      code: LiveUpdateErrorCode.NOT_SUPPORTED,
    });
  });
});

describe('throttling', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('sends the first update immediately', async () => {
    await update('order-1', content, { throttleMs: 1000 });
    expect(mockNative.update).toHaveBeenCalledTimes(1);
  });

  it('coalesces a burst into one call carrying the newest value', async () => {
    await update('order-1', { title: 'Order #1', progress: 0.1 }, { throttleMs: 1000 });
    expect(mockNative.update).toHaveBeenCalledTimes(1);

    // Three more inside the window. None should reach the bridge yet.
    void update('order-1', { title: 'Order #1', progress: 0.2 }, { throttleMs: 1000 });
    void update('order-1', { title: 'Order #1', progress: 0.3 }, { throttleMs: 1000 });
    const last = update(
      'order-1',
      { title: 'Order #1', progress: 0.4 },
      { throttleMs: 1000 },
    );
    expect(mockNative.update).toHaveBeenCalledTimes(1);

    jest.advanceTimersByTime(1000);
    await last;

    expect(mockNative.update).toHaveBeenCalledTimes(2);
    // Trailing edge: the value that lands is the newest, not the first queued.
    expect(mockNative.update).toHaveBeenLastCalledWith('order-1', {
      title: 'Order #1',
      progress: 0.4,
    });
  });

  it('resolves every coalesced caller, not just the last', async () => {
    await update('order-1', content, { throttleMs: 1000 });
    const first = update('order-1', { title: 'a' }, { throttleMs: 1000 });
    const second = update('order-1', { title: 'b' }, { throttleMs: 1000 });

    jest.advanceTimersByTime(1000);
    await expect(Promise.all([first, second])).resolves.toEqual([
      undefined,
      undefined,
    ]);
  });

  it('throttles each id on its own clock', async () => {
    await update('order-1', content, { throttleMs: 1000 });
    await update('order-2', content, { throttleMs: 1000 });
    expect(mockNative.update).toHaveBeenCalledTimes(2);
  });

  it('does not throttle when no window is given', async () => {
    await update('order-1', content);
    await update('order-1', content);
    expect(mockNative.update).toHaveBeenCalledTimes(2);
  });

  // The bug this guards: a queued update firing after end() re-posts the
  // activity in a stale in-progress state that nothing will ever clear.
  it('drops a queued update when the activity ends', async () => {
    await update('order-1', content, { throttleMs: 1000 });
    const queued = update('order-1', { title: 'stale' }, { throttleMs: 1000 });

    await end('order-1');
    jest.advanceTimersByTime(5000);

    await expect(queued).resolves.toBeUndefined();
    expect(mockNative.update).toHaveBeenCalledTimes(1);
    expect(mockNative.end).toHaveBeenCalledWith('order-1', 0);
  });

  it('drops a queued update when the activity restarts', async () => {
    await update('order-1', content, { throttleMs: 1000 });
    const queued = update('order-1', { title: 'stale' }, { throttleMs: 1000 });

    await start({ id: 'order-1', name: 'Order #1', content });
    jest.advanceTimersByTime(5000);

    await expect(queued).resolves.toBeUndefined();
    expect(mockNative.update).toHaveBeenCalledTimes(1);
  });
});

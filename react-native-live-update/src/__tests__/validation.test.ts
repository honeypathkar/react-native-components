import { LiveUpdateError, LiveUpdateErrorCode } from '../errors';
import { validateContent, validateId, validateStartOptions } from '../validate';

/** Assert both that it threw and that it named the field. */
function expectInvalid(fn: () => void, mentioning: string) {
  try {
    fn();
  } catch (error) {
    expect(error).toBeInstanceOf(LiveUpdateError);
    expect((error as LiveUpdateError).code).toBe(
      LiveUpdateErrorCode.INVALID_CONTENT,
    );
    expect((error as LiveUpdateError).message).toContain(mentioning);
    return;
  }
  throw new Error(`expected a validation error mentioning "${mentioning}"`);
}

describe('ids', () => {
  it('accepts an ordinary id', () => {
    expect(() => validateId('order-8231')).not.toThrow();
  });

  it.each([['', 'empty'], ['   ', 'empty']])(
    'rejects %j as blank',
    (id, mentioning) => expectInvalid(() => validateId(id), mentioning),
  );

  it('rejects a non-string id', () => {
    expectInvalid(() => validateId(42 as unknown as string), 'string');
  });
});

describe('content', () => {
  const valid = { title: 'Order #8231' };

  it('accepts the minimum', () => {
    expect(() => validateContent(valid)).not.toThrow();
  });

  it('accepts the full schema', () => {
    expect(() =>
      validateContent({
        title: 'Order #8231',
        message: 'Collect ₹137 on delivery',
        status: 'On way',
        progress: 0.66,
        stages: [
          { id: 'pickup', title: 'Pickup' },
          { id: 'on_the_way', title: 'On the way', weight: 3 },
          { id: 'delivered', title: 'Delivered', color: '#2E7D32' },
        ],
        endsAt: Date.now() + 600_000,
        icon: 'ic_delivery',
        trackerIcon: 'ic_scooter',
        color: '#C95942',
        deepLink: 'gram://orders/8231',
      }),
    ).not.toThrow();
  });

  it('requires a title', () => {
    expectInvalid(() => validateContent({}), 'content.title');
  });

  describe('progress', () => {
    it.each([0, 0.5, 1])('accepts the fraction %p', (progress) => {
      expect(() => validateContent({ ...valid, progress })).not.toThrow();
    });

    // The mistake people actually make. It pins the bar at 100% silently.
    it('rejects a percentage with an explanation', () => {
      expectInvalid(
        () => validateContent({ ...valid, progress: 65 }),
        'not a percentage',
      );
    });

    it('rejects a negative fraction', () => {
      expectInvalid(() => validateContent({ ...valid, progress: -0.1 }), 'progress');
    });

    it('rejects NaN', () => {
      expectInvalid(() => validateContent({ ...valid, progress: NaN }), 'number');
    });
  });

  describe('stages', () => {
    it('rejects an empty array, pointing at the alternative', () => {
      expectInvalid(
        () => validateContent({ ...valid, stages: [] }),
        'omit it for a plain progress bar',
      );
    });

    it('rejects duplicate stage ids', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            stages: [
              { id: 'pickup', title: 'Pickup' },
              { id: 'pickup', title: 'Also pickup' },
            ],
          }),
        'used more than once',
      );
    });

    it('rejects a zero weight, which would divide the track by nothing', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            stages: [{ id: 'a', title: 'A', weight: 0 }],
          }),
        'greater than 0',
      );
    });

    it('rejects more stages than the track can legibly draw', () => {
      const stages = Array.from({ length: 13 }, (_, i) => ({
        id: `s${i}`,
        title: `Stage ${i}`,
      }));
      expectInvalid(() => validateContent({ ...valid, stages }), '12 or fewer');
    });
  });

  describe('actions', () => {
    const action = { id: 'open', title: 'Open order' };

    it('accepts up to three', () => {
      expect(() =>
        validateContent({
          ...valid,
          actions: [
            action,
            { id: 'call', title: 'Call' },
            { id: 'nav', title: 'Navigate', deepLink: 'gram://nav/1' },
          ],
        }),
      ).not.toThrow();
    });

    it('accepts an action that ends the activity in place', () => {
      expect(() =>
        validateContent({
          ...valid,
          actions: [{ id: 'cancel', title: 'Cancel', endsActivity: true }],
        }),
      ).not.toThrow();
    });

    it('rejects a non-boolean endsActivity', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            actions: [{ id: 'cancel', title: 'Cancel', endsActivity: 'yes' }],
          }),
        'endsActivity',
      );
    });

    // Android silently drops the extras, so a fourth is a bug the developer
    // would otherwise only find by squinting at a device.
    it('rejects a fourth, naming the platform limit', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            actions: [1, 2, 3, 4].map((i) => ({ id: `a${i}`, title: `A${i}` })),
          }),
        'Android shows no more than that',
      );
    });

    it('rejects duplicate ids', () => {
      expectInvalid(
        () => validateContent({ ...valid, actions: [action, action] }),
        'used more than once',
      );
    });

    it('requires a title', () => {
      expectInvalid(
        () => validateContent({ ...valid, actions: [{ id: 'open' }] }),
        'content.actions[0].title',
      );
    });

    it('rejects a schemeless deepLink', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            actions: [{ ...action, deepLink: '/orders/1' }],
          }),
        'must include a scheme',
      );
    });
  });

  describe('auto progress', () => {
    const span = { startsAt: 1_700_000_000_000, endsAt: 1_700_000_060_000 };

    it('accepts a span to fill from', () => {
      expect(() =>
        validateContent({ ...valid, ...span, autoProgress: true }),
      ).not.toThrow();
    });

    // Without both stamps there is no span, and the track would simply sit at
    // zero on the device — a silent failure worth turning into a loud one.
    it('rejects autoProgress with no startsAt', () => {
      expectInvalid(
        () => validateContent({ ...valid, endsAt: span.endsAt, autoProgress: true }),
        'startsAt',
      );
    });

    it('rejects autoProgress with no endsAt', () => {
      expectInvalid(
        () => validateContent({ ...valid, startsAt: span.startsAt, autoProgress: true }),
        'endsAt',
      );
    });

    it('rejects a span that ends before it starts', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            startsAt: span.endsAt,
            endsAt: span.startsAt,
            autoProgress: true,
          }),
        'after startsAt',
      );
    });

    // Nothing to fill: the bar is the only thing autoProgress moves.
    it('rejects autoProgress with the bar switched off', () => {
      expectInvalid(
        () =>
          validateContent({
            ...valid,
            ...span,
            autoProgress: true,
            progressBar: false,
          }),
        'progressBar',
      );
    });

    it('rejects a startsAt that is not epoch milliseconds', () => {
      expectInvalid(
        () => validateContent({ ...valid, startsAt: 0 }),
        'startsAt',
      );
    });
  });

  describe('track style', () => {
    it('accepts each of the three renderers', () => {
      for (const trackStyle of ['segmented', 'even', 'continuous']) {
        expect(() => validateContent({ ...valid, trackStyle })).not.toThrow();
      }
    });

    it('rejects an unknown one, listing what it could have been', () => {
      expectInvalid(
        () => validateContent({ ...valid, trackStyle: 'solid' }),
        'segmented, even, continuous',
      );
    });

    it('accepts a track colour', () => {
      expect(() =>
        validateContent({ ...valid, trackColor: '#3A3A46' }),
      ).not.toThrow();
    });

    it('rejects a track colour that is not a hex colour', () => {
      expectInvalid(
        () => validateContent({ ...valid, trackColor: 'grey' }),
        'content.trackColor',
      );
    });
  });

  describe('colours', () => {
    it.each(['#fff', '#C95942', '#80C95942'])('accepts %s', (color) => {
      expect(() => validateContent({ ...valid, color })).not.toThrow();
    });

    it.each(['C95942', 'red', '#12345'])('rejects %s', (color) => {
      expectInvalid(() => validateContent({ ...valid, color }), 'hex colour');
    });
  });

  describe('deep links', () => {
    it.each(['gram://orders/1', 'https://gram.app/orders/1'])(
      'accepts %s',
      (deepLink) => {
        expect(() => validateContent({ ...valid, deepLink })).not.toThrow();
      },
    );

    it('rejects a path with no scheme, which Linking could never open', () => {
      expectInvalid(
        () => validateContent({ ...valid, deepLink: '/orders/1' }),
        'must include a scheme',
      );
    });
  });
});

describe('start options', () => {
  it('accepts a well-formed call', () => {
    expect(() =>
      validateStartOptions({
        id: 'order-8231',
        name: 'Order #8231',
        content: { title: 'Order #8231' },
      }),
    ).not.toThrow();
  });

  it('reports the nested field, not just "content"', () => {
    expectInvalid(
      () =>
        validateStartOptions({
          id: 'order-8231',
          name: 'Order #8231',
          content: { title: 'ok', stages: [{ id: '', title: 'Pickup' }] },
        }),
      'content.stages[0].id',
    );
  });
});

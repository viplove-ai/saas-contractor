import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppUpdateProvider, useAppUpdate, type UpdateCheck } from './appUpdate';

/**
 * What the profile screen's button is worth, which is entirely a question of what it says
 * back. A check that cannot tell "there is nothing newer" from "I could not ask" is a button
 * that teaches people to stop pressing it.
 *
 * <p>The seam is the service worker registration, because that is the thing no test
 * environment has. Everything above it — the states, the offer, the handover — is real.</p>
 */
const updateServiceWorker = vi.hoisted(() => vi.fn());
const registration = vi.hoisted(() => ({
  present: true,
  update: vi.fn(),
  waiting: null as object | null,
  installing: null as object | null,
}));
/** Set by the mocked hook so a test can make a version arrive the way workbox does. */
const arrive = vi.hoisted(() => ({ now: () => {} }));

vi.mock('virtual:pwa-register/react', async () => {
  const { useEffect, useState } = await vi.importActual<typeof import('react')>('react');
  return {
    useRegisterSW: (options: {
      onRegisteredSW?: (url: string, r: ServiceWorkerRegistration | undefined) => void;
    }) => {
      const [needRefresh, setNeedRefresh] = useState(false);
      useEffect(() => {
        arrive.now = () => setNeedRefresh(true);
        options.onRegisteredSW?.(
          '/sw.js',
          registration.present ? (registration as unknown as ServiceWorkerRegistration) : undefined,
        );
        // Registration happens once per mount, as it does in the plugin.
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, []);
      return {
        needRefresh: [needRefresh, setNeedRefresh],
        offlineReady: [false, () => {}],
        updateServiceWorker,
      };
    },
  };
});

let update: ReturnType<typeof useAppUpdate>;

function Probe() {
  update = useAppUpdate();
  return (
    <span data-testid="state">
      {update.lastCheck}
      {update.ready ? ' READY' : ''}
    </span>
  );
}

function state(): string {
  return screen.getByTestId('state').textContent ?? '';
}

function renderProvider() {
  return render(
    <AppUpdateProvider>
      <Probe />
    </AppUpdateProvider>,
  );
}

describe('the update check', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    registration.present = true;
    registration.waiting = null;
    registration.installing = null;
    registration.update.mockResolvedValue(undefined);
  });

  it('says so when this is the latest version', async () => {
    renderProvider();

    await act(() => update.check());

    expect(registration.update).toHaveBeenCalled();
    expect(state()).toBe<UpdateCheck>('CURRENT');
  });

  /**
   * A phone that cannot reach the server has not learnt that it is up to date, and saying so
   * would be the one answer that makes the button worse than nothing.
   */
  it('does not claim to be current when it could not ask', async () => {
    registration.update.mockRejectedValue(new Error('Failed to fetch'));
    renderProvider();

    await act(() => update.check());

    expect(state()).toBe<UpdateCheck>('UNREACHABLE');
  });

  /** A version found is announced by the offer, not by the check that turned it up. */
  it('stays quiet when the check finds a new version parked', async () => {
    registration.update.mockImplementation(async () => {
      registration.waiting = {};
    });
    renderProvider();

    await act(() => update.check());

    expect(state()).toBe<UpdateCheck>('IDLE');
  });

  it('reports having nothing to ask with when there is no worker', async () => {
    registration.present = false;
    renderProvider();

    await act(() => update.check());

    expect(registration.update).not.toHaveBeenCalled();
    expect(state()).toBe<UpdateCheck>('UNSUPPORTED');
  });
});

describe('the waiting version', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    registration.present = true;
    registration.waiting = null;
    registration.update.mockResolvedValue(undefined);
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload: vi.fn() },
    });
  });

  it('is offered as soon as it installs, and hands over when told to', async () => {
    renderProvider();
    expect(state()).not.toContain('READY');

    act(() => arrive.now());
    expect(state()).toContain('READY');

    await act(() => update.install());
    expect(updateServiceWorker).toHaveBeenCalledWith(true);
  });

  /**
   * "Later" is not "no". The worker stays parked and the offer is still there on the profile
   * screen, which is the whole point of putting it on a screen.
   */
  it('is kept when the offer is postponed', async () => {
    renderProvider();
    act(() => arrive.now());

    act(() => update.postpone());

    expect(state()).not.toContain('READY');
    expect(updateServiceWorker).not.toHaveBeenCalled();
  });
});

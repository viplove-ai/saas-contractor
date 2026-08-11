import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  OFFLINE_SESSION_GRACE_MS,
  readCachedSession,
  tokenStorage,
  type SessionUser,
} from '../../shared/session';
import { restoreSession } from './api';

/**
 * What happens when the app is opened and the network is not there.
 *
 * <p>The seam is the refresh call itself, because the whole behaviour turns on <em>how</em>
 * that call fails: a phone in a valley and a session the server has revoked are the same
 * event from React's side and must produce opposite outcomes. Everything below the seam is
 * real — the stored token, the cached profile, the clock — since those are the things the
 * old code got wrong.</p>
 */
const post = vi.hoisted(() => vi.fn());

vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>();
  return {
    ...actual,
    default: { ...actual.default, post, create: actual.default.create },
  };
});

const USER: SessionUser = {
  id: 'u-sup',
  username: 'vivek',
  fullName: 'Vivek Bisht',
  mustChangePassword: false,
  roles: ['SUPERVISOR'],
  permissions: ['attendance:create'],
  siteIds: ['site-a'],
  allSites: false,
};

/** No response at all — what a phone with no signal actually produces. */
function noAnswer(): AxiosError {
  return new AxiosError('Network Error');
}

/** The server answering, and refusing. */
function refused(status: number): AxiosError {
  const error = new AxiosError('refused');
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: { detail: 'The session is no longer valid.' },
  };
  return error;
}

function tokenPair() {
  return {
    data: {
      accessToken: 'access',
      tokenType: 'Bearer',
      expiresInSeconds: 900,
      refreshToken: 'rotated',
      user: USER,
    },
  };
}

/** Puts the device in the state a supervisor leaves it in overnight. */
function signedInYesterday(agoMs = 20 * 60 * 60_000): void {
  tokenStorage.setRefreshToken('stored-refresh-token');
  localStorage.setItem(
    'nirman.session',
    JSON.stringify({ user: USER, verifiedAt: new Date(Date.now() - agoMs).toISOString() }),
  );
}

describe('restoreSession', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('takes the server’s current profile when there is signal', async () => {
    signedInYesterday();
    post.mockResolvedValue(tokenPair());

    const restored = await restoreSession();

    expect(restored).toEqual({ state: 'VERIFIED', user: USER });
    // The rotation is persisted, or the next start-up presents a token that is already dead.
    expect(tokenStorage.getRefreshToken()).toBe('rotated');
    expect(readCachedSession()?.user.id).toBe('u-sup');
  });

  /**
   * The bug this whole feature exists to fix. The app used to treat "no answer" as "you are
   * not welcome here", which sent the supervisor to a sign-in screen that could not be
   * satisfied — and, worse, threw away the refresh token on the way, so the phone could not
   * sync even after it came back into signal.
   */
  it('opens on the cached profile when the refresh gets no answer', async () => {
    signedInYesterday();
    post.mockRejectedValue(noAnswer());

    const restored = await restoreSession();

    expect(restored.state).toBe('CACHED');
    expect(restored).toMatchObject({ user: { username: 'vivek' } });
    expect(tokenStorage.getRefreshToken()).toBe('stored-refresh-token');
  });

  it('signs out when the server answers and refuses', async () => {
    signedInYesterday();
    post.mockRejectedValue(refused(401));

    const restored = await restoreSession();

    expect(restored).toEqual({ state: 'SIGNED_OUT', reason: 'REJECTED' });
    // A token the server has already refused is worth nothing but a repeat of the refusal.
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(readCachedSession()).toBeNull();
  });

  it('stops honouring a cached profile once it is out of grace', async () => {
    signedInYesterday(OFFLINE_SESSION_GRACE_MS + 60_000);
    post.mockRejectedValue(noAnswer());

    const restored = await restoreSession();

    expect(restored).toEqual({ state: 'SIGNED_OUT', reason: 'OFFLINE_TOO_LONG' });
    expect(tokenStorage.getRefreshToken()).toBeNull();
  });

  it('is signed out, not offline, when the device never held a session', async () => {
    post.mockRejectedValue(noAnswer());

    const restored = await restoreSession();

    expect(restored).toEqual({ state: 'SIGNED_OUT', reason: 'NONE' });
    expect(post).not.toHaveBeenCalled();
  });

  /**
   * A profile with no token behind it cannot be refreshed and must not open the app: this is
   * what a half-cleared storage looks like after a browser prunes one key and not the other.
   */
  it('ignores a cached profile with no refresh token behind it', async () => {
    localStorage.setItem(
      'nirman.session',
      JSON.stringify({ user: USER, verifiedAt: new Date().toISOString() }),
    );

    const restored = await restoreSession();

    expect(restored).toEqual({ state: 'SIGNED_OUT', reason: 'NONE' });
    expect(readCachedSession()).toBeNull();
  });
});

describe('the shared refresh promise', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  /**
   * The reconnect races itself: the browser's `online` event wakes the sync queue and the
   * session revalidation in the same tick. Rotating one refresh token twice is what theft
   * looks like to the server, which answers by revoking the whole family — so the reconnect
   * that was meant to send the morning's work would instead sign the phone out.
   */
  it('rotates once when two callers restore at the same moment', async () => {
    signedInYesterday();
    post.mockResolvedValue(tokenPair());

    const [first, second] = await Promise.all([restoreSession(), restoreSession()]);

    expect(post).toHaveBeenCalledTimes(1);
    expect(first.state).toBe('VERIFIED');
    expect(second.state).toBe('VERIFIED');
  });
});

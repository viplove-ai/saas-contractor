import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tokenStorage, type SessionUser } from '../../shared/session';
import { forgetSession, login } from './api';

/**
 * What a sign-in does about the last sign-in.
 *
 * <p>The read caches on disk are keyed by URL, and a URL says nothing about who asked. They
 * are emptied when a session ends, which covers the ordinary case and not the one that
 * matters: a sign-out that never finished, or an app killed between the two. The sign-in has
 * to be able to tell, on its own, whether the handset has changed hands — and until this it
 * could not, because signing out erases the only record of who was here.</p>
 */
const post = vi.hoisted(() => vi.fn());
const clearApiCaches = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock('../../shared/apiClient', () => ({
  apiClient: { post },
  setAccessToken: vi.fn(),
}));

vi.mock('../../offline/apiCache', () => ({ clearApiCaches }));

function user(id: string, username: string): SessionUser {
  return {
    id,
    username,
    fullName: username,
    mustChangePassword: false,
    roles: ['SUPERVISOR'],
    permissions: ['attendance:create'],
    siteIds: ['site-a'],
    allSites: false,
  };
}

function answersWith(signedIn: SessionUser) {
  post.mockResolvedValue({
    data: {
      accessToken: 'access',
      tokenType: 'Bearer',
      expiresInSeconds: 900,
      refreshToken: 'refresh',
      user: signedIn,
    },
  });
}

describe('login', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    clearApiCaches.mockResolvedValue(undefined);
  });

  it('leaves the reference data alone when the same person signs back in', async () => {
    answersWith(user('u-sup', 'vivek'));
    await login('vivek', 'Nirman@123');
    forgetSession();
    clearApiCaches.mockClear();

    await login('vivek', 'Nirman@123');

    // They may be about to walk out of coverage with it, and it is theirs.
    expect(clearApiCaches).not.toHaveBeenCalled();
  });

  /**
   * The complaint this fixes. Signing out clears the caches and also clears the cached
   * profile, so the sign-in that followed had nothing to compare against and cleared nothing
   * — which was invisible until a sign-out did not finish, and then the evening supervisor
   * opened the app on the morning one's sites.
   */
  it('empties the read caches when the next person signs in', async () => {
    answersWith(user('u-sup', 'vivek'));
    await login('vivek', 'Nirman@123');
    forgetSession();
    clearApiCaches.mockClear();

    answersWith(user('u-eng', 'uttam'));
    await login('uttam', 'Nirman@123');

    expect(clearApiCaches).toHaveBeenCalledTimes(1);
  });

  it('has nothing to compare against on a handset nobody has used', async () => {
    answersWith(user('u-sup', 'vivek'));

    await login('vivek', 'Nirman@123');

    expect(clearApiCaches).not.toHaveBeenCalled();
    expect(tokenStorage.getRefreshToken()).toBe('refresh');
  });

  /** The marker is an id and nothing else: a phone that changes hands discloses no name. */
  it('remembers only who was here', async () => {
    answersWith(user('u-sup', 'vivek'));

    await login('vivek', 'Nirman@123');

    expect(localStorage.getItem('nirman.lastUser')).toBe('u-sup');
  });
});

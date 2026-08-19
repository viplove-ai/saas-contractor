import { apiClient, setAccessToken } from '../../shared/apiClient';
import { clearApiCaches } from '../../offline/apiCache';
import {
  clearCachedSession,
  isCacheExpired,
  isNetworkFailure,
  lastUserStorage,
  readCachedSession,
  refreshSession,
  tokenStorage,
  writeCachedSession,
  type SessionUser,
  type TokenResponse,
} from '../../shared/session';
import { forgetSelectedSite } from '../../shared/siteSelection';

export async function login(username: string, password: string): Promise<SessionUser> {
  /*
    Who was last here. The cached profile if the session is still standing — signing in over
    a live session is what a password change does — and otherwise the marker that outlives a
    sign-out, because by the time somebody is at the login screen the profile is long gone and
    the comparison below had nothing to compare against. It read "no idea" and cleared nothing,
    so the next shift inherited the last one's sites from the read caches.
  */
  const previous = readCachedSession()?.user.id ?? lastUserStorage.get();
  const { data } = await apiClient.post<TokenResponse>('/auth/login', { username, password });
  setAccessToken(data.accessToken);
  tokenStorage.setRefreshToken(data.refreshToken);
  writeCachedSession(data.user);
  /*
    Only when the handset changes hands. Clearing on every sign-in would throw away the
    reference data of somebody who simply signed out and back in, and they may be about to
    walk out of coverage with it.

    Awaited, unlike the fire-and-forget clear in forgetSession: the screens this sign-in is
    about to open start fetching the moment it returns, and a delete landing after them would
    take the new user's answers out of the cache along with the old user's.
  */
  if (previous && previous !== data.user.id) {
    await clearApiCaches();
  }
  lastUserStorage.set(data.user.id);
  return data.user;
}

/**
 * How a session was restored on start-up, or why it was not.
 *
 * <p>Three outcomes rather than a nullable user, because the caller has to do three
 * different things. VERIFIED is the ordinary case. CACHED means the app is open on a
 * profile the server has not confirmed yet and must say so and check again on reconnect.
 * SIGNED_OUT carries a reason, because "sign in again" with no explanation is what makes a
 * supervisor believe the app has lost their work.</p>
 */
export type SessionRestore =
  | { state: 'VERIFIED'; user: SessionUser }
  | { state: 'CACHED'; user: SessionUser; verifiedAt: string }
  | { state: 'SIGNED_OUT'; reason: SignedOutReason };

export type SignedOutReason =
  /** Nothing stored — a first visit, or a deliberate sign-out. */
  | 'NONE'
  /** The server refused the stored token: revoked, expired, or the password was changed. */
  | 'REJECTED'
  /** Offline too long for the cached profile to still be honoured. */
  | 'OFFLINE_TOO_LONG';

/**
 * Restores a session after a page load or PWA restart.
 *
 * <p>With signal, the stored refresh token buys a fresh access token and the current profile
 * in one call — which is also how a permission granted or a site unassigned since yesterday
 * reaches the device.</p>
 *
 * <p>With no signal, the app opens anyway on the last profile the server confirmed. The
 * refresh token is deliberately <b>kept</b> in that case. Clearing it was the bug this
 * replaces: a phone opened in a valley threw away the one credential that would have let it
 * sync when it came back out, so a supervisor who could not sign in also could not send the
 * work already sitting on the device.</p>
 */
export async function restoreSession(): Promise<SessionRestore> {
  const cached = readCachedSession();
  if (!tokenStorage.getRefreshToken()) {
    clearCachedSession();
    return { state: 'SIGNED_OUT', reason: 'NONE' };
  }
  try {
    const session = await refreshSession();
    setAccessToken(session.accessToken);
    return { state: 'VERIFIED', user: session.user };
  } catch (error) {
    if (isNetworkFailure(error)) {
      if (cached && !isCacheExpired(cached)) {
        return { state: 'CACHED', user: cached.user, verifiedAt: cached.verifiedAt };
      }
      /*
        Out of grace, or never signed in on this device. The tokens go, because a phone that
        cannot reach the server and has nothing recent to show for itself is exactly the one
        that should stop opening site data. Queued records are left alone — they belong to
        the device, not the session, and the sync screen still names them.
      */
      forgetSession();
      return {
        state: 'SIGNED_OUT',
        reason: cached ? 'OFFLINE_TOO_LONG' : 'NONE',
      };
    }
    // The server answered and said no. That is the one case where the stored token is worth
    // nothing and keeping it would only produce the same refusal on every reconnect.
    forgetSession();
    return { state: 'SIGNED_OUT', reason: 'REJECTED' };
  }
}

/**
 * Drops every trace of the session on this device: tokens, the cached profile, and the read
 * caches behind them.
 *
 * <p>The offline queue is deliberately left alone. Those records are the device's, not the
 * session's — a supervisor whose session was revoked overnight still marked yesterday's
 * muster, and it is still owed to the server. The sync screen keeps naming it, and it goes
 * out under whoever signs in next with the right to send it.</p>
 */
export function forgetSession(): void {
  tokenStorage.clear();
  clearCachedSession();
  // The site the last person was standing at, for the same reason the read caches go: a
  // site handset changes hands, and the next supervisor's first screen should not open on
  // somebody else's block.
  forgetSelectedSite();
  setAccessToken(null);
  void clearApiCaches();
}

/** Revokes the refresh family server-side; local state is cleared even if that call fails. */
export async function logout(): Promise<void> {
  const refreshToken = tokenStorage.getRefreshToken();
  try {
    if (refreshToken) {
      await apiClient.post('/auth/logout', { refreshToken });
    }
  } catch {
    // Signing out while offline still signs out locally; the family expires server-side.
  } finally {
    forgetSession();
  }
}

/**
 * Changes the caller's own password. The server revokes every refresh token as it does so —
 * a password change is meant to end the sessions the old password could still be holding
 * open — which would log this tab out too. So the new password is spent immediately on a
 * fresh sign-in, and the caller stays where they are with a profile that now says the
 * password has been set.
 */
export async function changePassword(
  username: string,
  currentPassword: string,
  newPassword: string,
): Promise<SessionUser> {
  await apiClient.post('/auth/password/change', { currentPassword, newPassword });
  return login(username, newPassword);
}

export async function fetchMe(): Promise<SessionUser> {
  const { data } = await apiClient.get<SessionUser>('/auth/me');
  return data;
}

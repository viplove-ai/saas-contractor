import { apiClient, setAccessToken } from '../../shared/apiClient';
import {
  refreshSession,
  tokenStorage,
  type SessionUser,
  type TokenResponse,
} from '../../shared/session';

export async function login(username: string, password: string): Promise<SessionUser> {
  const { data } = await apiClient.post<TokenResponse>('/auth/login', { username, password });
  setAccessToken(data.accessToken);
  tokenStorage.setRefreshToken(data.refreshToken);
  return data.user;
}

/**
 * Restores a session after a page load or PWA restart: the stored refresh token buys a
 * fresh access token and the profile in one call. Returns null when there is no stored
 * token or the server no longer honours it — the caller shows the login screen.
 */
export async function restoreSession(): Promise<SessionUser | null> {
  if (!tokenStorage.getRefreshToken()) {
    return null;
  }
  try {
    const session = await refreshSession();
    setAccessToken(session.accessToken);
    return session.user;
  } catch {
    tokenStorage.clear();
    setAccessToken(null);
    return null;
  }
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
    tokenStorage.clear();
    setAccessToken(null);
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

import axios from 'axios';

/**
 * Session plumbing shared by the api client and the auth feature: token persistence and
 * the refresh call. Lives in shared/ because apiClient needs it for silent renewal and
 * features must never be imported from shared/.
 *
 * Storage split: the access token stays in memory only (it dies with the tab, XSS gains
 * fifteen minutes at most), while the refresh token sits in localStorage so an installed
 * PWA survives a restart — its single-use rotation with family revocation on the server
 * is what caps the damage if it leaks.
 */

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

const REFRESH_TOKEN_KEY = 'nirman.refreshToken';

export const tokenStorage = {
  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  },
  setRefreshToken(token: string): void {
    localStorage.setItem(REFRESH_TOKEN_KEY, token);
  },
  clear(): void {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  },
};

/** Mirrors MeResponse on the server. */
export interface SessionUser {
  id: string;
  username: string;
  fullName: string;
  email?: string;
  mobile?: string;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  roles: string[];
  permissions: string[];
  siteIds: string[];
  allSites: boolean;
}

/** Mirrors TokenResponse on the server. */
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  refreshToken: string;
  user: SessionUser;
}

/**
 * Exchanges the stored refresh token for a fresh pair. Uses bare axios, not apiClient:
 * the interceptor that calls this must not recurse into itself. The rotated refresh token
 * is persisted before returning, because the old one is already dead on the server.
 */
export async function refreshSession(): Promise<TokenResponse> {
  const stored = tokenStorage.getRefreshToken();
  if (!stored) {
    throw new Error('Not signed in');
  }
  const { data } = await axios.post<TokenResponse>(`${API_BASE_URL}/auth/refresh`, {
    refreshToken: stored,
  });
  tokenStorage.setRefreshToken(data.refreshToken);
  return data;
}

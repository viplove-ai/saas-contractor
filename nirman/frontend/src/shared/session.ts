import axios from 'axios';

/**
 * Session plumbing shared by the api client and the auth feature: token persistence, the
 * cached profile an offline start reads, and the refresh call. Lives in shared/ because
 * apiClient needs it for silent renewal and features must never be imported from shared/.
 *
 * Storage split: the access token stays in memory only (it dies with the tab, XSS gains
 * fifteen minutes at most), while the refresh token sits in localStorage so an installed
 * PWA survives a restart — its single-use rotation with family revocation on the server
 * is what caps the damage if it leaks.
 */

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

const REFRESH_TOKEN_KEY = 'nirman.refreshToken';
const SESSION_KEY = 'nirman.session';
const LAST_USER_KEY = 'nirman.lastUser';

/**
 * Who was last signed in on this handset, kept across the sign-out that follows.
 *
 * <p>Deliberately outside {@code forgetSession}. Everything else about a session is dropped
 * when it ends, which is right, and it left the next sign-in with no way to answer the one
 * question it has to answer: is this the same person coming back, or the next shift? The
 * cached profile cannot answer it — signing out is what erases it — so the answer was always
 * "no idea", and the caches that a change of hands must empty were emptied on the strength of
 * a sign-out having completed. A sign-out in a valley, or an app killed part-way through one,
 * left the next user reading the last one's sites.</p>
 *
 * <p>It holds an id and nothing else: no name, no permissions, nothing that would be a
 * disclosure on a phone that changes hands. What it buys is a comparison.</p>
 */
export const lastUserStorage = {
  get(): string | null {
    return localStorage.getItem(LAST_USER_KEY);
  },
  set(userId: string): void {
    localStorage.setItem(LAST_USER_KEY, userId);
  },
};

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
 * The last profile the server confirmed, kept so the app opens with no signal.
 *
 * <p>This grants nothing. Every permission in it was already advisory on the client — the
 * server re-checks each one, and {@code SiteAccessGuard} re-reads the site assignments on
 * every request — so a tampered copy buys a screen the API will refuse anyway. What it buys
 * honestly is a supervisor's name on the masthead and the right nav for their role at
 * 6 a.m. in a valley, which is the difference between an app that works and a login screen
 * that cannot be satisfied.</p>
 */
export interface CachedSession {
  user: SessionUser;
  /** ISO instant of the last time the server confirmed this session. */
  verifiedAt: string;
}

/**
 * How long the app will open on a cached profile without the server confirming it again.
 *
 * <p>Two weeks. The number comes from the job rather than from a security convention: a
 * supervisor posted to a block with no coverage is out of contact for days at a stretch and
 * must be able to keep the muster the whole time, while a phone lost off the back of a lorry
 * should stop opening somebody's site data before the month is out. Revocation is not
 * delayed by this — the moment the phone sees the network the refresh token is checked, and
 * a revoked one signs the device out on the spot.</p>
 */
export const OFFLINE_SESSION_GRACE_MS = 14 * 24 * 60 * 60_000;

export function readCachedSession(): CachedSession | null {
  const raw = localStorage.getItem(SESSION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as CachedSession;
    // A half-written or older-shaped entry is treated as absent rather than trusted: the
    // cost of being wrong here is the login screen, and the cost of trusting it is a crash
    // on first render with no way for the user to get past it.
    return parsed?.user?.id && parsed.verifiedAt ? parsed : null;
  } catch {
    return null;
  }
}

export function writeCachedSession(user: SessionUser): void {
  const cached: CachedSession = { user, verifiedAt: new Date().toISOString() };
  localStorage.setItem(SESSION_KEY, JSON.stringify(cached));
}

export function clearCachedSession(): void {
  localStorage.removeItem(SESSION_KEY);
}

/** True once a cached profile is too old to open the app on. */
export function isCacheExpired(cached: CachedSession, now: number = Date.now()): boolean {
  const verified = Date.parse(cached.verifiedAt);
  // An unparseable timestamp is expired. A device whose clock has been wound back reads as
  // fresh either way, which is why this is a grace period and not an authorisation check.
  return Number.isNaN(verified) || now - verified > OFFLINE_SESSION_GRACE_MS;
}

/**
 * True when a request never got an answer — no signal, DNS gone, or a timeout.
 *
 * <p>The distinction this draws is the whole of the offline session story. A refresh that
 * fails with a 401 is the server saying this device is no longer welcome and the right
 * answer is to sign out; a refresh that fails with nothing at all says only that the lorry
 * is in a valley, and signing out for that is how a supervisor loses a morning's muster.</p>
 */
export function isNetworkFailure(error: unknown): boolean {
  return axios.isAxiosError(error) && !error.response;
}

let refreshInFlight: Promise<TokenResponse> | null = null;

/**
 * Exchanges the stored refresh token for a fresh pair, one rotation at a time.
 *
 * <p>Single-flight across every caller, and that is load-bearing rather than tidy. Two
 * callers race on exactly the event this feature adds: the browser fires {@code online},
 * which wakes the sync queue and the session revalidation in the same tick. Rotating the
 * same refresh token twice is what token theft looks like from the server's side, and it
 * answers by revoking the whole family — so the reconnect that was supposed to send the
 * morning's work would instead sign the phone out.</p>
 *
 * <p>Uses bare axios, not apiClient: the interceptor that calls this must not recurse into
 * itself. The rotated refresh token and the profile are persisted before returning, because
 * the old token is already dead on the server.</p>
 */
export function refreshSession(): Promise<TokenResponse> {
  refreshInFlight ??= runRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function runRefresh(): Promise<TokenResponse> {
  const stored = tokenStorage.getRefreshToken();
  if (!stored) {
    throw new Error('Not signed in');
  }
  const { data } = await axios.post<TokenResponse>(`${API_BASE_URL}/auth/refresh`, {
    refreshToken: stored,
  });
  tokenStorage.setRefreshToken(data.refreshToken);
  writeCachedSession(data.user);
  return data;
}

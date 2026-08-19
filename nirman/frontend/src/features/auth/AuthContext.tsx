import { CircularProgress, Stack } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { SessionUser } from '../../shared/session';
import {
  changePassword as apiChangePassword,
  login as apiLogin,
  logout as apiLogout,
  restoreSession,
  type SignedOutReason,
} from './api';

interface AuthContextValue {
  user: SessionUser | null;
  /** True until the stored session has been checked; guards render nothing sooner. */
  initialising: boolean;
  /**
   * True while the app is running on a profile the server has not confirmed since start-up.
   * Screens use it to explain why figures are missing, never to decide what is allowed.
   */
  unverified: boolean;
  /** When the server last confirmed this session, for the "working offline since" line. */
  verifiedAt: string | null;
  /** Why the last session ended, for the sign-in screen to explain. */
  signedOutReason: SignedOutReason;
  signIn: (username: string, password: string) => Promise<SessionUser>;
  signOut: () => Promise<void>;
  /** Changes the signed-in user's own password and keeps them signed in. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<SessionUser>;
  hasPermission: (code: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** How often an unconfirmed session asks the server again. Matches the sync queue's tick. */
const RECHECK_MS = 60_000;

export function AuthProvider({ children }: { children: ReactNode }) {
  /*
    The read cache is the session's, not the tab's.

    Every answer the server has given sits in it keyed by what was asked and not by who asked
    — the sites this account is posted to, the projects behind them, every figure on every
    dashboard. It is held in memory by a client created once for the life of the tab, and
    signing out did not touch it: the next person to sign in on the same tab was handed the
    last one's sites, still fresh for a minute apiece, with no request made that could have
    corrected them. On a desk machine where one person signs out and another signs in that is
    the whole of the complaint, and it is worse than a stale figure — it is somebody else's.
  */
  const queryClient = useQueryClient();
  const [user, setUser] = useState<SessionUser | null>(null);
  const [initialising, setInitialising] = useState(true);
  const [unverified, setUnverified] = useState(false);
  const [verifiedAt, setVerifiedAt] = useState<string | null>(null);
  const [signedOutReason, setSignedOutReason] = useState<SignedOutReason>('NONE');
  // Guards against two revalidations overlapping when the browser flaps between states.
  const checking = useRef(false);

  /**
   * Reads the stored session and settles the three states above.
   *
   * <p>Shared by the mount and by the reconnect below, because they want the same thing:
   * the server's current answer if it can be had, and last night's if it cannot.</p>
   */
  const check = useCallback(async () => {
    if (checking.current) return;
    checking.current = true;
    try {
      const restored = await restoreSession();
      if (restored.state === 'SIGNED_OUT') {
        setUser(null);
        setUnverified(false);
        setVerifiedAt(null);
        setSignedOutReason(restored.reason);
        return;
      }
      setUser(restored.user);
      setUnverified(restored.state === 'CACHED');
      setVerifiedAt(restored.state === 'CACHED' ? restored.verifiedAt : new Date().toISOString());
      setSignedOutReason('NONE');
    } finally {
      checking.current = false;
    }
  }, []);

  useEffect(() => {
    void check().finally(() => setInitialising(false));
  }, [check]);

  /**
   * Re-checks the session once the phone can reach the server again.
   *
   * <p>This closes the loop on an offline start: the app opened on a cached profile, and it
   * keeps asking the server whether that profile is still true. A role changed, a site
   * unassigned or the whole session revoked while the device was out of contact all land
   * here, rather than being discovered one refused request at a time.</p>
   *
   * <p>Three ways in, and the timer is not redundant. The {@code online} event fires only on
   * a transition, and the device that most needs re-checking never transitioned — it started
   * up already claiming to be online, with nothing at the other end. Coming back to the
   * foreground catches the phone leaving a pocket. The timer catches everything else, and
   * only runs while there is something to catch.</p>
   */
  useEffect(() => {
    if (!unverified) {
      return;
    }
    const recheck = () => void check();
    const onVisible = () => {
      if (document.visibilityState === 'visible') recheck();
    };
    window.addEventListener('online', recheck);
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(recheck, RECHECK_MS);
    return () => {
      window.removeEventListener('online', recheck);
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [check, unverified]);

  const signIn = useCallback(
    async (username: string, password: string) => {
      const signedIn = await apiLogin(username, password);
      /*
        Emptied on the way in as well as on the way out, and not only when the user differs.
        A session that ended in a way that never reached this code — the tab reloaded, the
        browser killed — leaves the cache behind it, and somebody who has just proved they
        have signal is the cheapest possible person to make refetch. The read caches on disk
        are handled a layer down, in login(), where the previous user is known.
      */
      queryClient.clear();
      setUser(signedIn);
      setUnverified(false);
      setVerifiedAt(new Date().toISOString());
      setSignedOutReason('NONE');
      return signedIn;
    },
    [queryClient],
  );

  const signOut = useCallback(async () => {
    await apiLogout();
    setUser(null);
    setUnverified(false);
    setVerifiedAt(null);
    setSignedOutReason('NONE');
    /*
      After the screens have been told to go, not before: clearing under a mounted table is a
      refetch nobody is waiting for, sent with a token that has just been thrown away.
    */
    queryClient.clear();
  }, [queryClient]);

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      if (!user) {
        throw new Error('Not signed in');
      }
      const refreshed = await apiChangePassword(user.username, currentPassword, newPassword);
      setUser(refreshed);
      setUnverified(false);
      setVerifiedAt(new Date().toISOString());
      return refreshed;
    },
    [user],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initialising,
      unverified,
      verifiedAt,
      signedOutReason,
      signIn,
      signOut,
      changePassword,
      hasPermission: (code: string) => user?.permissions.includes(code) ?? false,
    }),
    [user, initialising, unverified, verifiedAt, signedOutReason, signIn, signOut, changePassword],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}

/** Where someone still on their admin-issued password is held until they replace it. */
export const CHANGE_PASSWORD_PATH = '/profile';

/**
 * Route guard: children render only for a signed-in user.
 *
 * <p>It also holds anyone carrying an admin-issued password on the profile screen until
 * they set their own. A first-time password is handed over by phone or on paper and is
 * therefore known to at least two people; letting it keep working while its owner marks
 * attendance would put someone else's name on that attendance.</p>
 *
 * <p>One exception, and it is the whole point of the offline session: a user restored from
 * the cache is a signed-in user. The check that used to send them here — a network call that
 * cannot succeed — now happens the moment there is signal to make it with.</p>
 */
export function RequireAuth() {
  const { user, initialising, signedOutReason } = useAuth();
  const location = useLocation();

  if (initialising) {
    return (
      <Stack alignItems="center" justifyContent="center" sx={{ minHeight: '100dvh' }}>
        <CircularProgress />
      </Stack>
    );
  }
  if (!user) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname, reason: signedOutReason }}
      />
    );
  }
  if (user.mustChangePassword && location.pathname !== CHANGE_PASSWORD_PATH) {
    return <Navigate to={CHANGE_PASSWORD_PATH} replace />;
  }
  return <Outlet />;
}

import { CircularProgress, Stack } from '@mui/material';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
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
} from './api';

interface AuthContextValue {
  user: SessionUser | null;
  /** True until the stored session has been checked; guards render nothing sooner. */
  initialising: boolean;
  signIn: (username: string, password: string) => Promise<SessionUser>;
  signOut: () => Promise<void>;
  /** Changes the signed-in user's own password and keeps them signed in. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<SessionUser>;
  hasPermission: (code: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [initialising, setInitialising] = useState(true);

  useEffect(() => {
    let cancelled = false;
    restoreSession()
      .then((restored) => {
        if (!cancelled) {
          setUser(restored);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setInitialising(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    const signedIn = await apiLogin(username, password);
    setUser(signedIn);
    return signedIn;
  }, []);

  const signOut = useCallback(async () => {
    await apiLogout();
    setUser(null);
  }, []);

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      if (!user) {
        throw new Error('Not signed in');
      }
      const refreshed = await apiChangePassword(user.username, currentPassword, newPassword);
      setUser(refreshed);
      return refreshed;
    },
    [user],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initialising,
      signIn,
      signOut,
      changePassword,
      hasPermission: (code: string) => user?.permissions.includes(code) ?? false,
    }),
    [user, initialising, signIn, signOut, changePassword],
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
 */
export function RequireAuth() {
  const { user, initialising } = useAuth();
  const location = useLocation();

  if (initialising) {
    return (
      <Stack alignItems="center" justifyContent="center" sx={{ minHeight: '100dvh' }}>
        <CircularProgress />
      </Stack>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (user.mustChangePassword && location.pathname !== CHANGE_PASSWORD_PATH) {
    return <Navigate to={CHANGE_PASSWORD_PATH} replace />;
  }
  return <Outlet />;
}

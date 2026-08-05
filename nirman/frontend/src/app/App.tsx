import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { AuthProvider } from '../features/auth/AuthContext';
import { SyncProvider } from '../offline/SyncProvider';
import { router } from './router';
import { theme } from './theme';

/**
 * Query defaults are tuned for a site phone on 2G: retry twice, never refetch on window
 * focus (which fires constantly when a supervisor switches to the camera), and treat data
 * as fresh for a minute so a flaky connection is not hammered.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 2,
      refetchOnWindowFocus: false,
      staleTime: 60_000,
    },
    mutations: { retry: 0 },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {/*
          Inside AuthProvider: the queue sends with the signed-in user's token, and a drain
          fired before the session is restored would spend its first pass collecting 401s.
        */}
        <AuthProvider>
          <SyncProvider>
            <RouterProvider router={router} />
          </SyncProvider>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

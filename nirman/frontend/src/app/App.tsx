import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { AuthProvider } from '../features/auth/AuthContext';
import { SyncProvider } from '../offline/SyncProvider';
import { InstallPrompt } from '../shared/InstallPrompt';
import { createQueryClient } from './queryClient';
import { router } from './router';
import { theme } from './theme';

const queryClient = createQueryClient();

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
        {/*
          Outside the router on purpose: the offer is worth most on the login screen, which
          is where a first-time visitor lands, and it has no business changing per route.
        */}
        <InstallPrompt />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

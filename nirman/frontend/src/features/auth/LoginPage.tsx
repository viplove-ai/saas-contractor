import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, IconButton, InputAdornment, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { Wordmark } from '../../app/AppNav';
import { figure, graphPaper, inkEdge, marginNote } from '../../app/sketch';
import { tokens } from '../../app/theme';
import { useAuth } from './AuthContext';
import type { SignedOutReason } from './api';
import { loginSchema, type LoginForm } from './schema';

/**
 * Why the last session ended, said out loud.
 *
 * <p>An unexplained sign-in screen is how a supervisor concludes the app has eaten their
 * morning. Each of these is a different thing to do next — wait for signal, or go and find
 * the admin — and the screen is the only place left to say which.</p>
 */
const SIGNED_OUT_MESSAGE: Partial<Record<SignedOutReason, string>> = {
  REJECTED:
    'You were signed out. Your password may have been changed, or an admin ended the session. Sign in again to continue — nothing waiting on this phone has been lost.',
  OFFLINE_TOO_LONG:
    'This phone has been without a connection for too long to stay signed in. Sign in once with signal and it will keep working offline again — anything not sent yet is still here.',
};

/**
 * Sign-in. Still two fields, one button, 48px targets and errors in full sentences — this is
 * typed on a phone, outdoors, possibly with wet hands.
 *
 * <p>Three things were added and each earns its place. The labels moved above the fields
 * instead of floating in the outline, because a floating label is 12px of grey the moment
 * anybody types. The password gained a Show button, because the alternative on a cracked screen
 * in sunlight is a locked account. And the line about working without signal is on the first
 * screen rather than discovered later — it is the reason this app exists, and a supervisor who
 * does not know it will not trust an entry he cannot see arrive.</p>
 */
export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [serverError, setServerError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [online, setOnline] = useState(() => navigator.onLine);

  /*
    Watched rather than read once. This screen is where somebody sits waiting for a bar of
    signal, and the notice below has to clear itself when it arrives — being told to find a
    connection while holding a connection is how a working app looks broken.
  */
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    };
  }, []);

  /*
    Two ways in, because there are two ways out. A guard redirect carries the reason in
    router state; the api client's forced sign-out is a whole-page assign that has no state
    to carry, so it puts the reason in the query string instead.
  */
  const reason =
    (location.state as { reason?: SignedOutReason } | null)?.reason ??
    (searchParams.get('reason') === 'rejected' ? 'REJECTED' : undefined);
  const signedOutMessage = reason ? SIGNED_OUT_MESSAGE[reason] : undefined;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema) });

  const onSubmit = handleSubmit(async (form) => {
    setServerError(null);
    try {
      await signIn(form.username, form.password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Stack alignItems="center" justifyContent="center" sx={{ minHeight: '100dvh', p: 2, ...graphPaper }}>
      <Paper variant="outlined" sx={{ ...inkEdge(0), p: { xs: 3, sm: 4 }, width: '100%', maxWidth: 440 }}>
        <form onSubmit={onSubmit} noValidate>
          <Stack spacing={3}>
            <Stack spacing={2.5}>
              <Wordmark />
              <Box>
                <Typography variant="h1">Good morning.</Typography>
                <Typography color="text.secondary" sx={{ mt: 0.5 }}>
                  Sign in to open today's register.
                </Typography>
              </Box>
            </Stack>

            {signedOutMessage && !serverError && (
              <Alert severity="warning">{signedOutMessage}</Alert>
            )}

            {/*
              Said before the attempt rather than after it. Signing in is the one thing in
              this app that genuinely cannot be done without a connection — a password can
              only be checked by the server that holds it — so a supervisor staring at this
              screen in a valley deserves to know that up front instead of typing carefully
              twice and concluding the password is wrong.
            */}
            {!online && !serverError && (
              <Alert severity="warning">
                No connection. Signing in needs one — only this first step does. Once you are
                in, the app keeps working without signal.
              </Alert>
            )}

            {serverError && <Alert severity="error">{serverError}</Alert>}

            <Box>
              <FieldLabel htmlFor="username">Username</FieldLabel>
              <TextField
                id="username"
                autoComplete="username"
                autoCapitalize="none"
                autoFocus
                fullWidth
                error={!!errors.username}
                helperText={errors.username?.message}
                inputProps={{ style: { fontFamily: '"IBM Plex Mono", monospace' } }}
                {...register('username')}
              />
            </Box>

            <Box>
              <FieldLabel htmlFor="password">Password</FieldLabel>
              <TextField
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                fullWidth
                error={!!errors.password}
                helperText={errors.password?.message}
                inputProps={{ style: { fontFamily: '"IBM Plex Mono", monospace', letterSpacing: showPassword ? 0 : '0.18em' } }}
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      {/* A word, not an eye: the glyph is guessed, the word is read. */}
                      <IconButton
                        onClick={() => setShowPassword((shown) => !shown)}
                        aria-label={showPassword ? 'Hide password' : 'Show password'}
                        sx={{ minWidth: 48, minHeight: 48, borderRadius: 2, fontSize: '0.8125rem', fontWeight: 600, color: 'secondary.main' }}
                      >
                        {showPassword ? 'Hide' : 'Show'}
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
                {...register('password')}
              />
            </Box>

            <Button type="submit" variant="contained" color="secondary" size="large" disabled={isSubmitting} sx={{ minHeight: 56 }}>
              {isSubmitting ? 'Signing in…' : 'Sign in'}
            </Button>

            <Typography sx={{ ...marginNote, textAlign: 'center' }}>
              Works without signal — anything you enter waits on this phone and goes out later.
            </Typography>

            <Typography variant="body2" sx={{ textAlign: 'center', color: tokens.muted }}>
              Forgot your password? Ask your site admin to reset it.
            </Typography>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}

/** Above the field, at full contrast, and it stays put when somebody types. */
function FieldLabel({ htmlFor, children }: { htmlFor: string; children: React.ReactNode }) {
  return (
    <Typography
      component="label"
      htmlFor={htmlFor}
      sx={{ display: 'block', mb: 0.85, fontSize: '0.75rem', fontWeight: 600, color: 'text.primary' }}
    >
      {children}
    </Typography>
  );
}

/** Kept exported so a future "on the books today" panel can reuse the figure style. */
export const loginFigure = figure;

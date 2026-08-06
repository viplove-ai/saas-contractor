import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, IconButton, InputAdornment, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { Wordmark } from '../../app/AppNav';
import { figure, graphPaper, inkEdge, marginNote } from '../../app/sketch';
import { tokens } from '../../app/theme';
import { useAuth } from './AuthContext';
import { loginSchema, type LoginForm } from './schema';

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
  const [serverError, setServerError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);

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

import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from './AuthContext';
import { loginSchema, type LoginForm } from './schema';

/**
 * Sign-in. Kept deliberately plain: two fields, one button, 48px targets, errors in
 * full sentences — this is typed on a phone, outdoors, possibly with wet hands.
 */
export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [serverError, setServerError] = useState<string | null>(null);

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
    <Stack
      alignItems="center"
      justifyContent="center"
      sx={{ minHeight: '100dvh', bgcolor: 'background.default', p: 2 }}
    >
      <Paper
        elevation={0}
        sx={{ p: 4, width: '100%', maxWidth: 420, border: 1, borderColor: 'divider' }}
      >
        <form onSubmit={onSubmit} noValidate>
          <Stack spacing={3}>
            <Stack spacing={0.5}>
              <Typography variant="h1">Nirman</Typography>
              <Typography color="text.secondary">Sign in to continue</Typography>
            </Stack>

            {serverError && <Alert severity="error">{serverError}</Alert>}

            <TextField
              label="Username"
              autoComplete="username"
              autoCapitalize="none"
              autoFocus
              fullWidth
              error={!!errors.username}
              helperText={errors.username?.message}
              {...register('username')}
            />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              fullWidth
              error={!!errors.password}
              helperText={errors.password?.message}
              {...register('password')}
            />
            <Button
              type="submit"
              variant="contained"
              color="secondary"
              size="large"
              disabled={isSubmitting}
              sx={{ minHeight: 48 }}
            >
              {isSubmitting ? 'Signing in…' : 'Sign in'}
            </Button>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}

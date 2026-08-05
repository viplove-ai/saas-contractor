import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useResetPassword } from './api';
import { generatePassword } from './password';
import { resetPasswordSchema, type ResetPasswordForm } from './schema';
import type { AdminUser } from './types';

interface Props {
  user: AdminUser | null;
  onClose: () => void;
}

/**
 * Resets a member's password when they have lost it.
 *
 * <p>The password is shown in clear while the dialog is open and never afterwards: the
 * server stores only a hash and has no endpoint that returns one, so this dialog is the
 * single moment it can be read. Hence the confirmation step rather than a straight close —
 * an admin who dismissed it without writing the password down would have to reset again.</p>
 */
export function ResetPasswordDialog({ user, onClose }: Props) {
  const resetPassword = useResetPassword();
  const [serverError, setServerError] = useState<string | null>(null);
  const [issued, setIssued] = useState<string | null>(null);

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordForm>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { temporaryPassword: '' },
  });

  useEffect(() => {
    if (user) {
      setServerError(null);
      setIssued(null);
      reset({ temporaryPassword: generatePassword() });
    }
  }, [user, reset]);

  const submit = handleSubmit(async (values) => {
    if (!user) {
      return;
    }
    setServerError(null);
    try {
      await resetPassword.mutateAsync({ id: user.id, temporaryPassword: values.temporaryPassword });
      setIssued(values.temporaryPassword);
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={user !== null} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Reset password{user ? ` — ${user.fullName}` : ''}</DialogTitle>
      <DialogContent>
        {issued ? (
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Alert severity="success">
              Done. {user?.fullName} is signed out everywhere and must change this password at
              the next sign-in.
            </Alert>
            <DialogContentText>Give them this password now — it cannot be shown again.</DialogContentText>
            <TextField
              label="Password to hand over"
              value={issued}
              inputProps={{ readOnly: true }}
              // Mono, because this gets copied by eye onto a phone keypad.
              sx={{ '& input': { fontFamily: '"IBM Plex Mono", monospace', fontSize: '1.125rem' } }}
            />
          </Stack>
        ) : (
          <Stack spacing={2} sx={{ pt: 1 }}>
            {serverError && <Alert severity="error">{serverError}</Alert>}
            <DialogContentText>
              This ends every open session for {user?.username} straight away.
            </DialogContentText>
            <Controller
              control={control}
              name="temporaryPassword"
              render={({ field }) => (
                <Stack spacing={1}>
                  <TextField
                    {...field}
                    label="New temporary password"
                    error={!!errors.temporaryPassword}
                    helperText={errors.temporaryPassword?.message}
                  />
                  <Button
                    size="small"
                    onClick={() => field.onChange(generatePassword())}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    Generate another
                  </Button>
                </Stack>
              )}
            />
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        {issued ? (
          <Button variant="contained" color="secondary" onClick={onClose}>
            I have written it down
          </Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
              Reset password
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}

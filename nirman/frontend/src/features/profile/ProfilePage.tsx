import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Chip, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAppUpdate, type UpdateCheck } from '../../shared/appUpdate';
import { useAuth } from '../auth/AuthContext';
import { SignOutButton } from '../auth/SignOutButton';
import { roleLabels } from '../../shared/roles';
import { changePasswordSchema, type ChangePasswordForm } from './schema';

/** "2 days ago", for a timestamp whose exact minute nobody needs. */
function describeAge(iso: string): string {
  const hours = Math.floor((Date.now() - Date.parse(iso)) / 3_600_000);
  if (!Number.isFinite(hours) || hours < 1) return 'less than an hour ago';
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

/**
 * The member's own account: who the system thinks they are, and the one thing they can
 * change about it.
 *
 * <p>This screen is also the gate a new member lands on. Somebody handed them their first
 * password, so it is a shared secret until it is replaced — {@code RequireAuth} keeps them
 * here until it is, and the copy says why rather than leaving them to wonder what went
 * wrong with the screen they asked for.</p>
 */
export function ProfilePage() {
  const { user, changePassword, unverified, verifiedAt } = useAuth();
  const { ready: updateReady, install, check, lastCheck } = useAppUpdate();
  const navigate = useNavigate();
  const [serverError, setServerError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const forced = user?.mustChangePassword ?? false;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordForm>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit(async (form) => {
    setServerError(null);
    try {
      await changePassword(form.currentPassword, form.newPassword);
      reset({ currentPassword: '', newPassword: '', confirmPassword: '' });
      setDone(true);
      if (forced) {
        // They only came here because they were sent; put them where they were going.
        navigate('/home', { replace: true });
      }
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Stack spacing={3} sx={{ maxWidth: 520 }}>
      <Stack spacing={0.5}>
        <Typography variant="h1">Your account</Typography>
        <Typography color="text.secondary">{user?.fullName}</Typography>
      </Stack>

      <Paper elevation={0} sx={{ p: 3, border: 1, borderColor: 'divider' }}>
        <Stack spacing={1.5}>
          <Row label="Username" value={user?.username ?? '—'} />
          <Row
            label={(user?.roles.length ?? 0) > 1 ? 'Roles' : 'Role'}
            value={roleLabels(user?.roles ?? []).join(', ') || '—'}
          />
          <Row label="Email" value={user?.email || 'Not set'} />
          <Row label="Mobile" value={user?.mobile || 'Not set'} />
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="body2" color="text.secondary" sx={{ minWidth: 120 }}>
              Sites
            </Typography>
            {user?.allSites ? (
              <Chip size="small" label="All sites" />
            ) : (
              <Typography>{user?.siteIds.length ?? 0} assigned</Typography>
            )}
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Your name, roles and site postings are set by an administrator.
          </Typography>
          {/*
            Only when it is true, and phrased as the age of the answer rather than as a
            fault. What is on this screen while offline is the last thing the server said,
            and a role or a site posting changed since then has not reached the phone — this
            is the one place in the app where that gap is worth spelling out, because this is
            the screen somebody reads to check what they are allowed to do.
          */}
          {unverified && (
            <Alert severity="info">
              Shown from this phone's copy — there is no connection to check it against.
              {verifiedAt ? ` Last confirmed ${describeAge(verifiedAt)}.` : ''} It will update
              by itself when you have signal.
            </Alert>
          )}
        </Stack>
      </Paper>

      <Paper elevation={0} sx={{ p: 3, border: 1, borderColor: 'divider' }}>
        <form onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            <Typography variant="h3">Change your password</Typography>

            {forced && (
              <Alert severity="warning">
                You are still using the password an administrator gave you. Set your own to
                carry on — somebody else knows the current one.
              </Alert>
            )}
            {done && !forced && <Alert severity="success">Your password has been changed.</Alert>}
            {serverError && <Alert severity="error">{serverError}</Alert>}

            <TextField
              label={forced ? 'The password you were given' : 'Current password'}
              type="password"
              autoComplete="current-password"
              error={!!errors.currentPassword}
              helperText={errors.currentPassword?.message}
              {...register('currentPassword')}
            />
            <TextField
              label="New password"
              type="password"
              autoComplete="new-password"
              error={!!errors.newPassword}
              helperText={errors.newPassword?.message ?? 'At least 8 characters.'}
              {...register('newPassword')}
            />
            <TextField
              label="New password again"
              type="password"
              autoComplete="new-password"
              error={!!errors.confirmPassword}
              helperText={errors.confirmPassword?.message}
              {...register('confirmPassword')}
            />
            <Button
              type="submit"
              variant="contained"
              color="secondary"
              disabled={isSubmitting}
              sx={{ alignSelf: 'flex-start' }}
            >
              {isSubmitting ? 'Changing…' : 'Change password'}
            </Button>
          </Stack>
        </form>
      </Paper>

      {/*
        Beside the sign-out because that is where somebody goes when the app is not behaving
        and they are about to try the blunt instrument. A new version installs by itself and
        then waits to be let in, and the waiting is where updates go missing: an installed app
        on a site phone is never closed, so the offer that appears in the snackbar can be
        dismissed once and not seen again for a week. This is the same offer, on a screen, that
        does not go anywhere — and a way to ask when nothing has been offered at all.
      */}
      <Paper elevation={0} sx={{ p: 3, border: 1, borderColor: 'divider' }}>
        <Stack spacing={1.5}>
          <Typography variant="h3">App version</Typography>
          <Typography variant="body2" color="text.secondary">
            The app updates itself when it can. If it is behaving oddly, or somebody has told
            you a fix went out, ask for it here.
          </Typography>

          {updateReady ? (
            <Alert severity="info">
              A new version is ready. Anything you have not sent yet is kept.
            </Alert>
          ) : (
            <UpdateCheckNote state={lastCheck} />
          )}

          <Button
            variant={updateReady ? 'contained' : 'outlined'}
            color="secondary"
            disabled={lastCheck === 'CHECKING'}
            onClick={() => void (updateReady ? install() : check())}
            /* 48px: the app's floor for anything meant to be hit with a glove on. */
            sx={{ alignSelf: 'flex-start', minHeight: 48 }}
          >
            {updateReady
              ? 'Update now'
              : lastCheck === 'CHECKING'
                ? 'Checking…'
                : 'Check for updates'}
          </Button>
        </Stack>
      </Paper>

      {/*
        The only sign-out a phone has. RootLayout's is in a footer that is `md` and up, so
        without this one a supervisor on site cannot hand the phone to the next shift. Last
        on the page for the same reason it is last in the rail: leaving is not what anybody
        opened the screen to do.
      */}
      <Paper elevation={0} sx={{ p: 3, border: 1, borderColor: 'divider' }}>
        <Stack spacing={1.5}>
          <Typography variant="h3">Sign out</Typography>
          <Typography variant="body2" color="text.secondary">
            Anything not sent yet stays on this phone. Signing out does not discard it.
          </Typography>
          <SignOutButton
            variant="outlined"
            color="secondary"
            /* 48px: the app's floor for anything meant to be hit with a glove on. */
            sx={{ alignSelf: 'flex-start', minHeight: 48 }}
          />
        </Stack>
      </Paper>
    </Stack>
  );
}

/**
 * What the last check said, and nothing when nothing has been asked.
 *
 * <p>"Could not check" is deliberately not an error: on a site phone, no answer is the
 * ordinary state of the afternoon and says nothing about the app.</p>
 */
function UpdateCheckNote({ state }: { state: UpdateCheck }) {
  if (state === 'CURRENT') {
    return <Alert severity="success">You have the latest version.</Alert>;
  }
  if (state === 'UNREACHABLE') {
    return (
      <Alert severity="info">
        Could not check — there is no connection to the server. Try again when you have
        signal.
      </Alert>
    );
  }
  if (state === 'UNSUPPORTED') {
    return (
      <Alert severity="info">
        This browser cannot check for updates. Close the app and open it again to pick up a new
        version.
      </Alert>
    );
  }
  return null;
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" spacing={1}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 120 }}>
        {label}
      </Typography>
      <Typography>{value}</Typography>
    </Stack>
  );
}

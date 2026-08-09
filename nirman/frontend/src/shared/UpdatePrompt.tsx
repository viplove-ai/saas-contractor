import { Alert, Button, Snackbar, Stack, Typography } from '@mui/material';
import { useRegisterSW } from 'virtual:pwa-register/react';

/**
 * Offers a waiting version to somebody already using the app.
 *
 * <p>The service worker is registered with `registerType: 'prompt'`, which is a contract
 * with two halves: the new worker installs and then parks, and the app asks before letting
 * it take over. Without this half the worker waits forever — it only activates once every
 * client it controls is gone, and a phone that keeps the app in its task switcher never
 * closes the last one. An installed app then serves the version it was installed with for
 * as long as the supervisor keeps it open, which on a site phone is measured in weeks.</p>
 *
 * <p>Asking rather than reloading is the point. A supervisor part-way through a muster roll
 * must not have the screen swapped underneath them; the entry is theirs to finish. Nothing
 * is at risk either way — drafts live in IndexedDB and survive the reload — but losing the
 * half-typed row on screen is real, and only they know whether now is a good moment.</p>
 */
export function UpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();

  if (!needRefresh) {
    return null;
  }

  return (
    <Snackbar
      open
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      sx={{ maxWidth: 480 }}
    >
      <Alert
        severity="info"
        variant="filled"
        icon={false}
        sx={{ width: '100%', '& .MuiAlert-action': { alignItems: 'center' } }}
        action={
          <Stack direction="row" alignItems="center" spacing={0.5}>
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            <Button
              color="inherit"
              size="small"
              /* Reloads the page once the new worker has taken control. */
              onClick={() => void updateServiceWorker(true)}
              sx={{ minHeight: 48, whiteSpace: 'nowrap', fontWeight: 700 }}
            >
              Update
            </Button>
            {/*
              "Later" dismisses the offer, not the update: the worker stays waiting and the
              prompt returns next launch. Somebody mid-entry can say no without being asked
              again on the next keystroke.
            */}
            <Button
              color="inherit"
              size="small"
              onClick={() => setNeedRefresh(false)}
              sx={{ minHeight: 48, whiteSpace: 'nowrap' }}
            >
              Later
            </Button>
          </Stack>
        }
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          A new version is ready
        </Typography>
        <Typography variant="body2">
          Anything you have not sent yet is kept.
        </Typography>
      </Alert>
    </Snackbar>
  );
}

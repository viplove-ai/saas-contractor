import CloseIcon from '@mui/icons-material/Close';
import IosShareIcon from '@mui/icons-material/IosShare';
import { Alert, Box, Button, IconButton, Snackbar, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

/**
 * Offers the app to a first-time visitor who is still in a browser tab.
 *
 * <p>This matters more here than on a typical site: the whole app is built to survive a
 * site with no signal, and the offline queue only earns its keep once the app is on the
 * home screen — a supervisor who reaches it by typing a URL has to be online to get to
 * the screen that works offline. Chrome tells us when it is willing to install and we
 * hand that straight to the browser's own dialog; Safari never offers one, so iOS gets
 * the two-step instruction instead of a button that cannot exist.</p>
 *
 * <p>Asked once. A prompt that returns every visit is the thing people learn to dismiss
 * without reading, and the offer is worth nothing after that.</p>
 */

const DISMISSED_KEY = 'nirman.installPrompt.dismissed';

/** Fired by Chromium browsers when the PWA is installable; not in the DOM typings. */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/** True once the app is running from the home screen, under either vendor's spelling. */
function isInstalled(): boolean {
  const standaloneDisplay = window.matchMedia?.('(display-mode: standalone)').matches ?? false;
  // Safari never implemented display-mode and reports this instead.
  const iosStandalone = (window.navigator as { standalone?: boolean }).standalone === true;
  return standaloneDisplay || iosStandalone;
}

/**
 * iPadOS 13+ claims to be a Mac, so the platform string alone misses iPads — the touch
 * point count is what separates one from a real desktop Safari.
 */
function isIos(): boolean {
  const ua = window.navigator.userAgent;
  return /iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && navigator.maxTouchPoints > 1);
}

function wasDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === 'true';
  } catch {
    // Safari in private mode throws on localStorage. Rather than crash the shell, treat an
    // unreadable store as "not dismissed" — the worst case is being asked again.
    return false;
  }
}

function rememberDismissal(): void {
  try {
    localStorage.setItem(DISMISSED_KEY, 'true');
  } catch {
    /* nothing to do: the prompt simply reappears next visit */
  }
}

export function InstallPrompt() {
  const [installEvent, setInstallEvent] = useState<BeforeInstallPromptEvent | null>(null);
  const [showIosHint, setShowIosHint] = useState(false);

  useEffect(() => {
    if (isInstalled() || wasDismissed()) {
      return;
    }

    // iOS has no event to wait for, so the hint is on a timer. The delay keeps it off the
    // first paint of the login screen: somebody arriving to sign in should see the form
    // before they are offered anything.
    if (isIos()) {
      const timer = window.setTimeout(() => setShowIosHint(true), 4000);
      return () => window.clearTimeout(timer);
    }

    const onBeforeInstallPrompt = (event: Event) => {
      // Suppress Chrome's own mini-infobar so ours is the only offer on screen.
      event.preventDefault();
      setInstallEvent(event as BeforeInstallPromptEvent);
    };
    // Fires when the install completes by any route, including the browser's own menu.
    const onInstalled = () => {
      setInstallEvent(null);
      rememberDismissal();
    };

    window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  const handleInstall = async () => {
    if (!installEvent) {
      return;
    }
    // The saved event is single-use: clear it either way, since a second prompt() throws.
    setInstallEvent(null);
    await installEvent.prompt();
    const { outcome } = await installEvent.userChoice;
    if (outcome === 'dismissed') {
      // Declining the browser's dialog is an answer, not an accident. Don't ask again.
      rememberDismissal();
    }
  };

  const handleClose = () => {
    setInstallEvent(null);
    setShowIosHint(false);
    rememberDismissal();
  };

  const open = installEvent !== null || showIosHint;
  if (!open) {
    return null;
  }

  return (
    <Snackbar
      open
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      // Stays until answered: it competes with nothing, and a supervisor reading it on a
      // phone in daylight should not have it time out mid-sentence.
      sx={{ maxWidth: 480 }}
    >
      <Alert
        severity="info"
        variant="filled"
        icon={false}
        sx={{ width: '100%', '& .MuiAlert-action': { alignItems: 'center' } }}
        /*
          Both buttons are spelled out here rather than leaving the close to `onClose`:
          MUI drops its own close button as soon as an `action` is supplied, so on Chrome
          — the one case with an Install button — there would be no way to say no.
        */
        action={
          <Stack direction="row" alignItems="center" spacing={0.5}>
            {installEvent && (
              <Button
                color="inherit"
                size="small"
                onClick={handleInstall}
                sx={{ minHeight: 48, whiteSpace: 'nowrap' }}
              >
                Install
              </Button>
            )}
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            <IconButton
              color="inherit"
              aria-label="Close"
              onClick={handleClose}
              sx={{ width: 48, height: 48 }}
            >
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
        }
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          Install Nirman on this phone
        </Typography>
        {installEvent ? (
          <Typography variant="body2">
            Opens from your home screen and keeps working when the site has no signal.
          </Typography>
        ) : (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
            <Typography variant="body2" component="span">
              Tap
            </Typography>
            <IosShareIcon fontSize="small" aria-label="the Share button" />
            <Typography variant="body2" component="span">
              then “Add to Home Screen”.
            </Typography>
          </Box>
        )}
      </Alert>
    </Snackbar>
  );
}

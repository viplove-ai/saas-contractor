import { Alert, Button, Snackbar, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { OUTSTANDING_SIGNATURE } from '../../shared/session';
import { useAuth } from '../auth/AuthContext';
import { SIGNATURE_SECTION_ID } from './SignatureCard';

const DISMISSED_KEY = 'nirman.signaturePrompt.dismissed';

/**
 * Asks for what the account is still missing, once per sitting.
 *
 * <p>The server says what is outstanding ({@code MeResponse.outstanding}) and this reads the
 * list rather than deciding for itself, so the day a second requirement is added the prompt
 * needs a sentence and not a rule. Today the list holds one word: every member of the firm
 * signs something, and none of them had a signature on file when the documents started
 * drawing one.</p>
 *
 * <p>Dismissable, and dismissed for the sitting rather than for good. "Later" is an answer to
 * the question of whether now is a good moment, not to whether the signature will be supplied;
 * a prompt that came back on the next keystroke would be dismissed for ever by reflex, and one
 * that never came back would leave the report printing over a blank line for months. So it is
 * remembered in session storage, which a closed tab forgets, and keyed to the member, because
 * a site handset changes hands.</p>
 *
 * <p>Not shown on the profile screen itself — the card it points at is already on the page —
 * and not while a member is being held there to change an admin-issued password, which is
 * the one thing that comes before this.</p>
 */
export function SignaturePrompt() {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [dismissed, setDismissed] = useState(true);

  const key = user ? `${DISMISSED_KEY}.${user.id}` : null;
  useEffect(() => {
    if (!key) return;
    try {
      setDismissed(sessionStorage.getItem(key) === 'true');
    } catch {
      setDismissed(false);
    }
  }, [key]);

  const outstanding = user?.outstanding?.includes(OUTSTANDING_SIGNATURE) ?? false;
  if (!user || !outstanding || dismissed || user.mustChangePassword || pathname === '/profile') {
    return null;
  }

  const later = () => {
    setDismissed(true);
    try {
      if (key) sessionStorage.setItem(key, 'true');
    } catch {
      // A store that will not write only means being asked again next time.
    }
  };

  return (
    <Snackbar open anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }} sx={{ maxWidth: 480 }}>
      <Alert
        severity="warning"
        variant="filled"
        icon={false}
        sx={{ width: '100%', '& .MuiAlert-action': { alignItems: 'center' } }}
        action={
          <Stack direction="row" alignItems="center" spacing={0.5}>
            {/* 48px: the app's floor for anything meant to be hit with a glove on. */}
            <Button
              color="inherit"
              size="small"
              onClick={() => {
                later();
                navigate(`/profile#${SIGNATURE_SECTION_ID}`);
              }}
              sx={{ minHeight: 48, whiteSpace: 'nowrap', fontWeight: 700 }}
            >
              Upload now
            </Button>
            <Button color="inherit" size="small" onClick={later} sx={{ minHeight: 48 }}>
              Later
            </Button>
          </Stack>
        }
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          Your signature is not on file
        </Typography>
        <Typography variant="body2">
          The documents you sign print it where your name goes. It takes a minute.
        </Typography>
      </Alert>
    </Snackbar>
  );
}

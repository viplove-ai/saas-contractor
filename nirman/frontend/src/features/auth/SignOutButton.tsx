import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  type ButtonProps,
} from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { offlineDb, UNSENT_STATUSES } from '../../offline/db';
import { useAuth } from './AuthContext';

/**
 * Signing out, with the one question worth asking first.
 *
 * <p>Signing out does not discard the queue — those records belong to the device and the
 * sync screen keeps naming them. What it does is hand the phone to somebody who cannot send
 * them, which on a site handset is frequently the literal next thing that happens: the shift
 * changes, the phone changes hands, and a morning's muster sits behind a sign-in nobody
 * thinks to make. So the question is asked once, and only when there is something waiting to
 * make it worth asking.</p>
 *
 * <p>Lives beside the auth context rather than in shared/ because it needs {@link useAuth},
 * and shared/ does not import from features/. Both the desk footer and the phone's profile
 * screen use it, which is also why the guard is here and not copied into each.</p>
 */
export function SignOutButton(props: ButtonProps) {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);

  const unsent = useLiveQuery(
    () => offlineDb.drafts.where('status').anyOf(UNSENT_STATUSES).count(),
    [],
    0,
  );

  const signOutNow = async () => {
    setConfirming(false);
    await signOut();
    navigate('/login', { replace: true });
  };

  return (
    <>
      <Button
        {...props}
        onClick={() => {
          if (unsent > 0) {
            setConfirming(true);
            return;
          }
          void signOutNow();
        }}
      >
        {props.children ?? 'Sign out'}
      </Button>

      <Dialog open={confirming} onClose={() => setConfirming(false)}>
        <DialogTitle>
          {unsent} record{unsent === 1 ? '' : 's'} not sent yet
        </DialogTitle>
        <DialogContent>
          <DialogContentText>
            They stay on this phone and are not lost. But they can only go out while somebody
            with the right to send them is signed in here — so if you are handing the phone
            over, send them first.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button component={Link} to="/sync" onClick={() => setConfirming(false)}>
            Review them
          </Button>
          <Button onClick={() => setConfirming(false)}>Stay signed in</Button>
          <Button onClick={() => void signOutNow()} color="error">
            Sign out anyway
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

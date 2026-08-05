import { Alert, Button } from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { offlineDb, UNSENT_STATUSES } from '../offline/db';

/**
 * Visible only when something is actually waiting. An always-on connection indicator
 * becomes wallpaper; a count of unsent records does not.
 *
 * <p>A record stuck on a decision is called out separately and in a louder colour, because
 * the two states need opposite things from the reader. Records merely waiting need nothing —
 * saying so is the reassurance. A conflict needs somebody to open the screen, and it will
 * still be there tomorrow if the banner lets it hide inside a count.</p>
 */
export function OfflineBanner() {
  const counts = useLiveQuery(
    async () => {
      const waiting = await offlineDb.drafts.where('status').anyOf(UNSENT_STATUSES).count();
      const stuck = await offlineDb.drafts.where('status').anyOf('CONFLICT', 'FAILED').count();
      return { waiting, stuck };
    },
    [],
    { waiting: 0, stuck: 0 },
  );

  if (!counts.waiting) {
    return null;
  }

  return (
    <Alert
      severity={counts.stuck > 0 ? 'error' : 'warning'}
      square
      action={
        <Button component={Link} to="/sync" color="inherit" size="small">
          Review
        </Button>
      }
    >
      {counts.stuck > 0
        ? `${counts.stuck} record${counts.stuck === 1 ? '' : 's'} need${counts.stuck === 1 ? 's' : ''} a decision`
        : `${counts.waiting} record${counts.waiting === 1 ? '' : 's'} not sent yet`}
    </Alert>
  );
}

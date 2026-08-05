import { Alert, Button } from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { offlineDb } from '../offline/db';

/**
 * Visible only when something is actually waiting. An always-on connection indicator
 * becomes wallpaper; a count of unsent records does not.
 */
export function OfflineBanner() {
  const pendingCount = useLiveQuery(
    () => offlineDb.drafts.where('status').anyOf('DRAFT', 'PENDING', 'FAILED').count(),
    [],
    0,
  );

  if (!pendingCount) {
    return null;
  }

  return (
    <Alert
      severity="warning"
      square
      action={
        <Button component={Link} to="/sync" color="inherit" size="small">
          Review
        </Button>
      }
    >
      {pendingCount} record{pendingCount === 1 ? '' : 's'} not sent yet
    </Alert>
  );
}

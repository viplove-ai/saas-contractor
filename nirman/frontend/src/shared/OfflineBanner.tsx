import { Alert, Button } from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { offlineDb, UNSENT_STATUSES } from '../offline/db';

/**
 * Visible only when something is actually the matter. An always-on connection indicator
 * becomes wallpaper; a count of unsent records does not, and neither does a line that
 * appears the moment the signal goes.
 *
 * <p>Three states, ranked, and only the top one shows. A record stuck on a decision is
 * loudest and named separately, because it will still be there tomorrow if the banner lets
 * it hide inside a count. Records merely waiting need nothing from the reader — saying so is
 * the reassurance. And plain "no connection" with nothing waiting is the quietest of the
 * three, because on its own it is not a problem: it is the answer to why the dashboard is
 * empty, which is a question a supervisor would otherwise answer by deciding the app is
 * broken.</p>
 *
 * @param offline whether to show that third state. Passed in rather than read from
 *   {@code navigator.onLine} here, because that flag answers "is there a network" and the
 *   question worth putting on screen is "did anything reach the server" — a phone on a wifi
 *   with no uplink says yes to the first and no to the second. The shell composes the answer
 *   from both the browser's opinion and whether the session could be checked.
 */
export function OfflineBanner({ offline = false }: { offline?: boolean }) {
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
    if (!offline) {
      return null;
    }
    return (
      <Alert severity="info" square>
        No connection. You can carry on — what you enter is kept on this phone and goes out by
        itself.
      </Alert>
    );
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

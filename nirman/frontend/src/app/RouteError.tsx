import { Alert, Button, Paper, Stack, Typography } from '@mui/material';
import { Link, useRouteError } from 'react-router-dom';
import { inkEdge, marginNote } from './sketch';

/**
 * Every route on the table is code-split, so opening one asks the network for a file. The
 * service worker precaches those files and covers this from the second visit onward — but
 * the gap is real: a screen added by an update that has not been fetched yet, a phone that
 * lost signal between the first load and the first navigation, a cache evicted by a browser
 * short of space. The import rejects, and without something here React Router renders its
 * own developer-facing stack trace on a supervisor's phone.
 *
 * <p>The distinction the screen draws is the one the reader needs: a screen that is missing
 * because there is no signal is a wait, not a fault. Either way the two things worth saying
 * are said — nothing entered has been lost, and here is the way back to a screen that
 * works.</p>
 */
export function RouteError() {
  const error = useRouteError();
  const offline = isChunkLoadFailure(error) || navigator.onLine === false;

  return (
    <Stack alignItems="center" sx={{ py: 6, px: 2 }}>
      <Paper variant="outlined" sx={{ ...inkEdge(0), p: 3, maxWidth: 480, width: '100%' }}>
        <Stack spacing={2.5}>
          <Typography variant="h1">
            {offline ? 'This screen needs signal' : 'This screen did not open'}
          </Typography>

          <Alert severity={offline ? 'warning' : 'error'}>
            {offline
              ? 'It has not been downloaded to this phone yet, and there is no connection to fetch it with. It will open once you have signal.'
              : messageOf(error)}
          </Alert>

          <Typography sx={marginNote}>
            Nothing you have entered is lost. Anything not yet sent is still on this phone and
            still listed under Not sent yet.
          </Typography>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            {/*
              A reload rather than a router navigation: the chunk failed to fetch, and asking
              the router to render the same route again would fail on the same cached
              rejection. A reload is the only thing that genuinely retries it.
            */}
            <Button
              variant="contained"
              color="secondary"
              onClick={() => window.location.reload()}
              sx={{ minHeight: 48 }}
            >
              Try again
            </Button>
            <Button component={Link} to="/sync" variant="outlined" sx={{ minHeight: 48 }}>
              Not sent yet
            </Button>
            <Button component={Link} to="/today" sx={{ minHeight: 48 }}>
              Today
            </Button>
          </Stack>
        </Stack>
      </Paper>
    </Stack>
  );
}

/**
 * Whether the router failed because a code-split file could not be fetched.
 *
 * <p>Matched on the message because that is all the browsers give: a failed dynamic import
 * rejects with a plain TypeError, and the wording differs per engine — Chrome says it failed
 * to fetch the module, Firefox says there was an error loading it, Safari says the import
 * was not able to be fetched. All three are the same event.</p>
 */
function isChunkLoadFailure(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '');
  return /dynamically imported module|Importing a module script failed|Failed to fetch/i.test(
    message,
  );
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Something went wrong opening this screen.';
}

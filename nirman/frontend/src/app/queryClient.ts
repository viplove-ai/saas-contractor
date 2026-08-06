import { QueryClient } from '@tanstack/react-query';

/**
 * Query defaults are tuned for a site phone on 2G: retry twice, never refetch on window
 * focus (which fires constantly when a supervisor switches to the camera), and treat data
 * as fresh for a minute so a flaky connection is not hammered.
 *
 * <p>Mutations run in `always` network mode, and that is the line that makes the offline
 * story work at all. React Query's default is to <em>pause</em> a mutation while the browser
 * reports no connection — it never calls the function, and fires it on reconnect instead.
 * That is a reasonable default for an app whose saves are plain POSTs, and exactly wrong
 * here: {@link ../offline/saveOrQueue saveOrQueue} is what decides between sending and
 * queueing, and a paused mutation means it is never asked. A supervisor marking a muster in
 * a valley would get a button stuck on "Saving…", nothing on the device, and nothing to see
 * on the sync screen — the queue would be empty because nothing ever reached it.</p>
 *
 * <p>Queries keep the default `online` mode on purpose. A read with no connection has
 * nothing useful to do: pausing it keeps the last answer on screen and resumes by itself,
 * where retrying would only fill the screen with failures a supervisor cannot act on.</p>
 *
 * <p>A factory rather than a singleton so tests get a clean cache per case while still
 * running against the configuration the app actually ships.</p>
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: 2,
        refetchOnWindowFocus: false,
        staleTime: 60_000,
      },
      mutations: { retry: 0, networkMode: 'always' },
    },
  });
}

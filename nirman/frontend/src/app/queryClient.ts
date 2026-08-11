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
 * <p>Queries run in `offlineFirst`, and the reason is the service worker. React Query's
 * default `online` mode does not fire a query at all while the browser reports no
 * connection — which was correct when nothing was cached and a read with no signal really
 * did have nothing to do, and is now the thing standing between a supervisor and the roster
 * sitting in the worker's cache two inches away. `offlineFirst` fires the request once, lets
 * it reach the worker, and pauses only the retries. The screens that read from endpoints
 * nobody caches are no worse off: they fail once and say so, instead of hanging on a
 * spinner that resolves whenever the lorry clears the ridge.</p>
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
        networkMode: 'offlineFirst',
      },
      mutations: { retry: 0, networkMode: 'always' },
    },
  });
}

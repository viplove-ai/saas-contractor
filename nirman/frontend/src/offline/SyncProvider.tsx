import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { drain, type DrainSummary } from './queue';

/** How often a drain is attempted while the app is open and online. */
const TICK_MS = 60_000;

interface SyncContextValue {
  online: boolean;
  syncing: boolean;
  /** What the last completed pass did, or null before the first one. */
  lastSummary: DrainSummary | null;
  syncNow: () => Promise<void>;
}

const SyncContext = createContext<SyncContextValue>({
  online: true,
  syncing: false,
  lastSummary: null,
  syncNow: async () => {},
});

export function useSync(): SyncContextValue {
  return useContext(SyncContext);
}

/**
 * Runs the queue for as long as the app is open.
 *
 * <p>Four triggers, because each covers a case the others miss. The {@code online} event is
 * the one that matters at site and fires the moment the lorry clears the ridge. The timer
 * covers the connection that is nominally up and actually dead, which is the normal state of
 * a phone at the edge of a cell and something the browser never reports. A drain on mount
 * covers the app being reopened after being killed with records still queued, which is every
 * morning.</p>
 *
 * <p>And the fourth is the app coming back to the foreground. It exists because
 * {@code online} is not the reliable event it looks like: the browser fires it on a
 * <em>transition</em>, and a phone that started up with no route to the server never
 * transitioned — it has reported itself online the whole time. That device would otherwise
 * wait out the full timer before trying, and the moment a supervisor takes the phone out of
 * a pocket is both the likeliest moment for the signal to have returned and the one where
 * waiting is most visible.</p>
 */
export function SyncProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  );
  const [syncing, setSyncing] = useState(false);
  const [lastSummary, setLastSummary] = useState<DrainSummary | null>(null);
  // Held in a ref so the effect below does not re-subscribe every time a drain finishes.
  const inFlight = useRef(false);

  const syncNow = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSyncing(true);
    try {
      const summary = await drain();
      setLastSummary(summary);
      if (summary.sent > 0 || summary.uploaded > 0) {
        /*
          Everything, rather than the keys the sent records touched. A batch of attendance
          moves the roster, the verification queue, the site dashboard, the data-quality
          findings and the DPR prefill; enumerating that list here would put a copy of every
          module's cache layout in the sync engine, and the copy would go stale the first
          time a module added a screen. A reconnect is rare and a refetch is cheap.
        */
        await queryClient.invalidateQueries();
      }
    } finally {
      inFlight.current = false;
      setSyncing(false);
    }
  }, [queryClient]);

  useEffect(() => {
    const goOnline = () => {
      setOnline(true);
      void syncNow();
    };
    const goOffline = () => setOnline(false);
    const onVisible = () => {
      if (document.visibilityState === 'visible' && navigator.onLine) {
        setOnline(true);
        void syncNow();
      }
    };
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(() => {
      if (navigator.onLine) void syncNow();
    }, TICK_MS);
    void syncNow();
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [syncNow]);

  return (
    <SyncContext.Provider value={{ online, syncing, lastSummary, syncNow }}>
      {children}
    </SyncContext.Provider>
  );
}

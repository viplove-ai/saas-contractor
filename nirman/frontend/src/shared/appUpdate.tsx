import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';

/**
 * The one place that owns the service worker, and the only place that asks it for a newer
 * version of the app.
 *
 * <p>The worker is registered with `registerType: 'prompt'`, which parks a new version
 * rather than swapping it in under somebody mid-entry. That half has always worked. What was
 * missing is the other half: <em>somebody has to look</em>. A browser checks for a new worker
 * on a navigation, and an installed PWA on a site phone does not navigate — it is opened in
 * the morning, kept in the task switcher all day, and reopened the next morning onto the same
 * document. A version published on Tuesday could sit unnoticed for a fortnight, which is the
 * complaint this exists to answer.</p>
 *
 * <p>So two things happen here. The app asks by itself, on the same three triggers
 * {@code SyncProvider} and {@code AuthProvider} use and for the same reason — the timer, the
 * foreground, and {@code online} — because none of the three alone catches the phone that
 * started up already claiming to be online. And a person can ask, from the profile screen,
 * which is what turns "I think mine is out of date" from a call to the office into a button.</p>
 *
 * <p>One registration, held here, rather than a {@code useRegisterSW} in each component that
 * wants it: two calls are two Workbox instances racing to register the same script, and the
 * offer would then be answered by whichever of them heard about it.</p>
 */

/** What the last look for a new version found. */
export type UpdateCheck =
  /** Nothing has been asked yet, or the answer was yes and {@code ready} now carries it. */
  | 'IDLE'
  | 'CHECKING'
  /** The server was asked, and this is the current version. */
  | 'CURRENT'
  /** No answer — the phone cannot reach the server to ask. */
  | 'UNREACHABLE'
  /** Nothing to ask with: a browser without service workers, or the dev server. */
  | 'UNSUPPORTED';

interface AppUpdateValue {
  /** True once a new version has installed and is waiting for permission to take over. */
  ready: boolean;
  /** Dismisses the offer. The waiting version stays waiting; it is not discarded. */
  postpone: () => void;
  /** Hands over to the waiting version and reloads onto it. */
  install: () => Promise<void>;
  /** Asks now whether there is a newer version. */
  check: () => Promise<void>;
  /** What the last check a person asked for found. */
  lastCheck: UpdateCheck;
}

/**
 * How often the app looks by itself. An hour: often enough that a fix published in the
 * morning reaches the site the same day, rare enough to be nothing on a metered connection —
 * the check is a conditional request for one script.
 */
const AUTO_CHECK_MS = 60 * 60_000;

/**
 * The floor between two automatic checks. Without it, a phone going in and out of a pocket
 * on a walk round the site would ask on every foreground.
 */
const AUTO_CHECK_GAP_MS = 15 * 60_000;

/**
 * If the handover does not reload the page by itself, reload anyway.
 *
 * <p>The plugin reloads on the {@code controlling} event, which is the right thing when it
 * arrives. When it does not — a worker that redundantly errored, a browser that swallowed the
 * message — the person who pressed Update is left looking at the version they pressed it to
 * leave. Reloading regardless costs nothing: they asked for exactly this, and a reload with
 * no new worker behind it lands back on the same screen.</p>
 */
const HANDOVER_FALLBACK_MS = 3_000;

const AppUpdateContext = createContext<AppUpdateValue>({
  ready: false,
  postpone: () => {},
  install: async () => {},
  check: async () => {},
  lastCheck: 'UNSUPPORTED',
});

/**
 * The update state, for the snackbar that offers it and the profile screen that asks for it.
 *
 * <p>Usable with no provider above it — it then reports a version nothing can check, which is
 * the truth in a test and on a browser with no service worker.</p>
 */
export function useAppUpdate(): AppUpdateValue {
  return useContext(AppUpdateContext);
}

export function AppUpdateProvider({ children }: { children: ReactNode }) {
  const registration = useRef<ServiceWorkerRegistration | null>(null);
  const lastAsked = useRef(0);
  const [lastCheck, setLastCheck] = useState<UpdateCheck>('IDLE');

  const {
    needRefresh: [ready, setReady],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(_url, swRegistration) {
      registration.current = swRegistration ?? null;
    },
  });

  /**
   * Asks the server whether the script behind this app has changed.
   *
   * <p>Quietly for the automatic triggers, which must not make the profile screen flicker
   * through "checking" while nobody is looking at it, and loudly for the button, whose whole
   * value is that it answers.</p>
   */
  const ask = useCallback(async (quiet: boolean) => {
    const current = registration.current;
    if (!current) {
      if (!quiet) setLastCheck('UNSUPPORTED');
      return;
    }
    lastAsked.current = Date.now();
    if (!quiet) setLastCheck('CHECKING');
    try {
      await current.update();
      /*
        `waiting` is a new version parked and `installing` is one on its way; either means the
        answer was yes, and the offer appears on its own. The check then says nothing rather
        than printing "you have the latest version" over the top of an offer to replace it.
      */
      const found = Boolean(current.waiting ?? current.installing);
      if (!quiet) setLastCheck(found ? 'IDLE' : 'CURRENT');
    } catch {
      // update() rejects when the script cannot be fetched, which on a site phone is the
      // ordinary case and not a fault.
      if (!quiet) setLastCheck('UNREACHABLE');
    }
  }, []);

  const check = useCallback(() => ask(false), [ask]);

  useEffect(() => {
    const quietly = () => {
      if (Date.now() - lastAsked.current < AUTO_CHECK_GAP_MS) {
        return;
      }
      void ask(true);
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') quietly();
    };
    window.addEventListener('online', quietly);
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(quietly, AUTO_CHECK_MS);
    return () => {
      window.removeEventListener('online', quietly);
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [ask]);

  const install = useCallback(async () => {
    await updateServiceWorker(true);
    window.setTimeout(() => window.location.reload(), HANDOVER_FALLBACK_MS);
  }, [updateServiceWorker]);

  const postpone = useCallback(() => setReady(false), [setReady]);

  const value = useMemo<AppUpdateValue>(
    () => ({ ready, postpone, install, check, lastCheck: ready ? 'IDLE' : lastCheck }),
    [ready, postpone, install, check, lastCheck],
  );

  return <AppUpdateContext.Provider value={value}>{children}</AppUpdateContext.Provider>;
}

import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';

/**
 * The site the user is working on, shared by every screen that asks for one.
 *
 * <p>Each screen used to keep its own site in a `useState` and default it to the first site
 * the account could reach. On the one-site account that was invisible; on an engineer running
 * four blocks it meant choosing Kausani on the day screen, opening the muster, and being put
 * back on Almora — the site he had just said he was not at. The choice is the same choice on
 * every screen, so it is held in one place.</p>
 *
 * <p>It lives in `localStorage` as well as in memory. A supervisor's phone gets closed and
 * reopened all day, and the site he is standing at does not change when it does. It is
 * cleared by {@code forgetSession} for the reason the read caches are: a site handset changes
 * hands, and the next person should not inherit the last one's site.</p>
 *
 * <p>This is a convenience and never an authorisation. The id is only ever fed into requests
 * the server scopes itself — {@code SiteAccessGuard} re-checks the assignment on every one of
 * them — so a tampered entry buys a screen the API refuses.</p>
 */

const STORAGE_KEY = 'nirman.selectedSite';

let selected: string = readStored();
const listeners = new Set<() => void>();

function readStored(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    // Safari in private mode throws on access rather than returning null. A screen that
    // cannot remember the site still has to open.
    return '';
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function snapshot(): string {
  return selected;
}

/** The site every screen opens on next. Writing the same id again notifies nobody. */
export function rememberSelectedSite(siteId: string): void {
  if (siteId === selected) {
    return;
  }
  selected = siteId;
  try {
    if (siteId) {
      localStorage.setItem(STORAGE_KEY, siteId);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Held in memory for this session either way, which is the part the screens read.
  }
  listeners.forEach((listener) => listener());
}

/** Drops the remembered site. Called when the device forgets the session. */
export function forgetSelectedSite(): void {
  rememberSelectedSite('');
}

interface SiteLike {
  id: string;
}

interface Options {
  /**
   * The screen offers "every site" as an answer, and choosing it must not erase the
   * remembered site — a register widened to look at everything is a thing you do for a
   * minute, not a decision about where you are working.
   */
  allowAll?: boolean;
}

/**
 * The selected site, narrowed to what this account can actually reach.
 *
 * <p>Returns the same pair a `useState` did, so a screen swaps two blocks for one line. The
 * remembered site is used when it is in the list; otherwise the first site is, because most
 * supervisors reach exactly one and nobody should choose from a list of one.</p>
 *
 * @param sites the sites this screen can offer, or undefined while they load
 */
export function useSelectedSite(
  sites: readonly SiteLike[] | undefined,
  options: Options = {},
): [string, (siteId: string) => void] {
  const remembered = useSyncExternalStore(subscribe, snapshot, snapshot);
  // "Every site" is this screen's answer and nobody else's, so it is held here rather than
  // in the shared store.
  const [widened, setWidened] = useState(false);

  const known = Boolean(remembered) && (sites ?? []).some((site) => site.id === remembered);
  /*
    With nothing remembered, a screen that needs one site takes the first. A screen that can
    show every site shows every site — except to somebody who reaches exactly one, for whom
    "all my sites" and "my site" are the same list and only one of them fills in the forms
    the screen opens.
  */
  const fallback = options.allowAll
    ? (sites?.length === 1 ? sites[0]!.id : '')
    : (sites?.[0]?.id ?? '');
  const effective = widened ? '' : known ? remembered : fallback;

  /*
    The fallback is written back, so the first screen opened settles the site for the rest.
    Only when there is something to fall back to and nothing remembered that fits — otherwise
    a screen listing one site would keep overwriting the choice made on a screen listing four.
  */
  useEffect(() => {
    if (!widened && !known && fallback) {
      rememberSelectedSite(fallback);
    }
  }, [widened, known, fallback]);

  const choose = useCallback(
    (siteId: string) => {
      if (siteId === '') {
        setWidened(true);
        return;
      }
      setWidened(false);
      rememberSelectedSite(siteId);
    },
    [],
  );

  return [effective, choose];
}

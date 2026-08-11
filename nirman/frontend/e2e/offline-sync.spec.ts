import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * The Phase 7 exit criterion that only a browser can prove: <b>mark attendance and add an
 * expense offline, then online.</b>
 *
 * <p>No backend runs here — the whole e2e suite is a production build served on its own — so
 * the API is answered by route handlers standing in for it. That is not a compromise in this
 * particular test: what is under test is the client's behaviour when the network is missing
 * and then present, and a stub is the only way to control that boundary precisely enough to
 * assert on it. The server's half of the same guarantee is covered by
 * {@code OfflineReplayIntegrationTest} against a real PostgreSQL.</p>
 *
 * <p>What is asserted is the join between the two: that after a spell with no signal, the
 * device sends each record exactly once, under the id it recorded it with. Every POST body
 * is captured, so "exactly once" is a count of requests rather than an absence of visible
 * errors.</p>
 */

const SITES = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const ROSTER = {
  siteId: 'site-a',
  standardShiftHours: 7,
  overtimeReasonRequiredAboveHours: 10,
  periodLocked: false,
  entries: [
    { workerId: 'w-1', workerCode: 'W-101', workerName: 'Karam Singh', normalRate: 625 },
    { workerId: 'w-2', workerCode: 'W-102', workerName: 'Ramesh Kumar', normalRate: 450 },
  ],
};

const CATEGORIES = [
  { id: 'cat-misc', code: 'MISC', name: 'Miscellaneous', active: true },
  { id: 'cat-site', code: 'MISC-SITE', name: 'Site Expenses', parentId: 'cat-misc', active: true },
];

const EMPTY_PAGE = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

/** Every POST the app made, in order, so "sent once" is countable rather than inferred. */
interface Recorder {
  posts: { url: string; body: unknown }[];
  /** Flipped to simulate the lorry clearing the ridge. */
  online: boolean;
}

/**
 * Signs in without a server. The app keeps the access token in memory and the refresh token
 * in storage, so the session is seeded before the first navigation rather than typed in —
 * this test is about the queue, and driving a stubbed login screen would only add a way for
 * it to fail for an unrelated reason.
 */
async function signIn(page: Page, recorder: Recorder): Promise<void> {
  // On the context rather than the page, because once the service worker takes control it is
  // the worker that issues the API requests, and a page-level route never sees those.
  await page.context().route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url()).pathname;

    if (!recorder.online) {
      // What a phone with no signal actually produces: a failed connection, not a 5xx.
      await route.abort('internetdisconnected');
      return;
    }

    if (request.method() === 'POST') {
      recorder.posts.push({ url, body: request.postDataJSON() as unknown });
    }

    if (url.endsWith('/auth/refresh')) {
      await json(route, {
        accessToken: 'access-token',
        tokenType: 'Bearer',
        expiresInSeconds: 900,
        refreshToken: 'refresh-token',
        user: USER,
      });
      return;
    }
    if (url.endsWith('/attendance/roster')) {
      await json(route, ROSTER);
      return;
    }
    if (url.endsWith('/attendance/bulk')) {
      await json(route, { accepted: 2, unchanged: 0, rejected: 0, outcomes: [] });
      return;
    }
    if (url.endsWith('/sites')) {
      await json(route, SITES);
      return;
    }
    if (url.endsWith('/expense-categories')) {
      await json(route, CATEGORIES);
      return;
    }
    if (url.endsWith('/expenses') && request.method() === 'POST') {
      await json(route, { id: 'e-1', expenseNumber: 'EXP-2026-0001', workflowStatus: 'DRAFT' }, 201);
      return;
    }
    if (url.endsWith('/expenses')) {
      await json(route, EMPTY_PAGE);
      return;
    }
    await json(route, EMPTY_PAGE);
  });

  await page.goto('/login');
  await page.evaluate((user) => {
    window.localStorage.setItem('nirman.refreshToken', 'refresh-token');
    // What a real sign-in leaves behind, and what an offline start reads instead of the
    // network. Seeded alongside the token so the two never disagree.
    window.localStorage.setItem(
      'nirman.session',
      JSON.stringify({ user, verifiedAt: new Date().toISOString() }),
    );
  }, USER);
}

const USER = {
  id: 'u-sup',
  username: 'vivek',
  fullName: 'Vivek Bisht',
  roles: ['SUPERVISOR'],
  permissions: ['attendance:create', 'expense:create', 'expense:read'],
  siteIds: ['site-a'],
  allSites: false,
  mustChangePassword: false,
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * The lorry goes into the valley. {@code setOffline} is what the browser itself reports —
 * it flips {@code navigator.onLine}, fires the offline event and fails requests — which is
 * the state the app branches on, so nothing here has to be faked in the page.
 */
async function goOffline(page: Page, recorder: Recorder): Promise<void> {
  recorder.online = false;
  await page.context().setOffline(true);
}

/** And clears the ridge. The browser fires `online`, which is what wakes the queue. */
async function comeOnline(page: Page, recorder: Recorder): Promise<void> {
  recorder.online = true;
  await page.context().setOffline(false);
}

/**
 * Gets the app to the state a supervisor's phone is actually in: installed, with the worker
 * in charge of serving it.
 *
 * <p>Two steps, because the app registers with {@code registerType: 'prompt'} and a prompting
 * worker deliberately does not claim the page that installed it — claiming would swap the
 * screen out from under somebody mid-entry, which is the whole reason for prompting. So the
 * first load installs the worker and the second is the one it controls. That second load is
 * not a contrivance for the test: it is the supervisor closing the app and opening it again,
 * which is precisely the journey under test here.</p>
 */
async function installServiceWorker(page: Page): Promise<void> {
  await page.waitForFunction(
    async () => {
      const registration = await navigator.serviceWorker.ready;
      return registration.active !== null;
    },
    null,
    { timeout: 30_000 },
  );
  await page.reload();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, {
    timeout: 30_000,
  });
}

test.describe('offline then online', () => {
  test('a muster marked with no signal is kept, then sent exactly once', async ({ page }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);

    await page.goto('/attendance/mark');
    await expect(page.getByText('Karam Singh')).toBeVisible();

    // The lorry goes into the valley.
    await goOffline(page, recorder);

    await page.getByRole('button', { name: 'Mark all present' }).click();
    await page.getByRole('button', { name: /^Save 2 mark/ }).click();

    // Not an error, and it must not read like one: the marks are on the phone.
    await expect(page.getByText(/saved on this phone/)).toBeVisible();
    await expect(page.getByText(/records? not sent yet/)).toBeVisible();
    expect(recorder.posts.filter((post) => post.url.endsWith('/attendance/bulk'))).toHaveLength(0);

    await comeOnline(page, recorder);

    // The banner clears because the record has gone, which is the only claim worth making
    // on the screen — the count behind it is what the assertion below checks.
    await expect(page.getByText(/records? not sent yet/)).toBeHidden({ timeout: 15_000 });
    const musters = recorder.posts.filter((post) => post.url.endsWith('/attendance/bulk'));
    expect(musters).toHaveLength(1);
    expect(musters[0]?.body).toMatchObject({ siteId: 'site-a', date: expect.any(String) });

    // The exit criterion in its strongest form: a second reconnect must not re-send it. The
    // ids are the same, so the server would absorb it — but the queue should not rely on that.
    // The event is dispatched rather than toggled, because the browser only fires `online` on
    // an actual transition and the point here is to run the handler again.
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await page.waitForTimeout(1000);
    expect(recorder.posts.filter((post) => post.url.endsWith('/attendance/bulk'))).toHaveLength(1);
  });

  test('an expense booked with no signal keeps its id and is sent once', async ({ page }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);

    await page.goto('/expenses/new');
    await expect(page.getByLabel('What was it for')).toBeVisible();

    await goOffline(page, recorder);

    await page.getByLabel('What kind').click();
    await page.getByRole('option', { name: 'Site Expenses' }).click();
    await page.getByLabel('What was it for').fill('Cartage for cement');
    await page.getByLabel('Amount before tax').fill('3400');
    await page.getByRole('button', { name: 'Save as draft' }).click();

    await expect(page.getByText(/saved on this phone/)).toBeVisible();
    expect(recorder.posts.filter((post) => post.url.endsWith('/expenses'))).toHaveLength(0);

    await comeOnline(page, recorder);

    await expect(page.getByText(/records? not sent yet/)).toBeHidden({ timeout: 15_000 });
    const bookings = recorder.posts.filter((post) => post.url.endsWith('/expenses'));
    expect(bookings).toHaveLength(1);
    const body = bookings[0]?.body as { id: string; description: string };
    expect(body.description).toBe('Cartage for cement');
    // The id was generated on the device before the network was ever consulted. That is what
    // makes the send safe to repeat, and it is the one field worth asserting the shape of.
    expect(body.id).toMatch(/^[0-9a-f-]{36}$/);
  });

  /**
   * The sync screen is the remedy behind the banner, so it has to be reachable and readable
   * while there is still no signal — which is precisely when nothing else on it can load.
   */
  test('the sync screen names what is waiting while still offline', async ({ page }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);

    await page.goto('/attendance/mark');
    await expect(page.getByText('Karam Singh')).toBeVisible();
    await goOffline(page, recorder);
    await page.getByRole('button', { name: 'Mark all present' }).click();
    await page.getByRole('button', { name: /^Save 2 mark/ }).click();
    await expect(page.getByText(/saved on this phone/)).toBeVisible();

    await page.getByRole('link', { name: 'Review' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Not sent yet' })).toBeVisible();
    await expect(page.getByText('No connection')).toBeVisible();
    // Named, not counted: "1 record" tells a supervisor nothing about which morning it is.
    await expect(page.getByText(/attendance mark\(s\) — KSN-A/)).toBeVisible();
    await expect(page.getByText(/Nothing is lost/)).toBeVisible();
  });
});

/**
 * Reopening the app with no signal at all — the case the whole offline story stands or falls
 * on, and the one that used to fail hardest. The session is restored by asking the server to
 * exchange a refresh token, so with no server there was no session, and the app answered by
 * showing a sign-in screen that could not be satisfied. Worse, it cleared the stored token on
 * the way, so the phone could not sync afterwards either: a supervisor who lost signal
 * overnight lost the whole day's ability to use the app, and the queued records with it.
 */
test.describe('reopened with no signal', () => {
  test('the app opens on the last known session instead of an unanswerable sign-in', async ({
    page,
  }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);

    // One good visit, so the worker precaches the shell — this is "the app is installed".
    await page.goto('/today');
    await expect(page).toHaveURL(/\/today$/);
    await installServiceWorker(page);

    // The app is closed and the phone is out of coverage before it is opened again.
    await goOffline(page, recorder);
    await page.reload();

    await expect(page).toHaveURL(/\/today$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Good morning.' })).toBeHidden();
    // The line that says the app is working rather than broken.
    await expect(page.getByText(/No connection\. You can carry on/)).toBeVisible();

    // And the credential that lets it sync later is still there. Clearing it was the bug.
    const token = await page.evaluate(() => window.localStorage.getItem('nirman.refreshToken'));
    expect(token).toBe('refresh-token');
  });

  test('work entered on a cold offline start is kept and sent on reconnect', async ({ page }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);

    await page.goto('/attendance/mark');
    await expect(page.getByText('Karam Singh')).toBeVisible();
    await installServiceWorker(page);
    // Fetched once with signal so there is something in the worker's cache to fall back to.
    await expect(page.getByText('Karam Singh')).toBeVisible();

    await goOffline(page, recorder);
    await page.reload();

    // The roster comes from the worker's cache: without it there are no names to tick, and
    // marking the muster is the one thing this app exists to do with no signal.
    await expect(page.getByText('Karam Singh')).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Mark all present' }).click();
    await page.getByRole('button', { name: /^Save 2 mark/ }).click();
    await expect(page.getByText(/saved on this phone/)).toBeVisible();

    await comeOnline(page, recorder);
    /*
      Dispatched rather than left to the browser, and for a reason worth stating: a document
      loaded while the connection was already gone reports itself online the whole time, so
      there is no transition for the browser to fire an event about. That is not an artefact
      of the test — it is the exact condition this journey creates, and the app's answer to
      it is the foreground and timer triggers. This stands in for the foreground one so the
      assertion does not have to sit through the timer.
    */
    await page.evaluate(() => window.dispatchEvent(new Event('online')));

    await expect(page.getByText(/records? not sent yet/)).toBeHidden({ timeout: 20_000 });
    expect(recorder.posts.filter((post) => post.url.endsWith('/attendance/bulk'))).toHaveLength(1);
  });

  /**
   * The other end of the grace period. A phone that has not seen the server in a fortnight
   * stops opening site data — and says why, because "sign in again" with no explanation is
   * how a supervisor concludes the app has eaten their work.
   */
  test('a session too old to honour signs out and explains itself', async ({ page }) => {
    const recorder: Recorder = { posts: [], online: true };
    await signIn(page, recorder);
    await page.goto('/today');
    await expect(page).toHaveURL(/\/today$/);
    await installServiceWorker(page);

    await page.evaluate((user) => {
      window.localStorage.setItem(
        'nirman.session',
        JSON.stringify({
          user,
          verifiedAt: new Date(Date.now() - 20 * 24 * 60 * 60 * 1000).toISOString(),
        }),
      );
    }, USER);

    await goOffline(page, recorder);
    await page.reload();

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByText(/without a connection for too long/)).toBeVisible();
  });
});

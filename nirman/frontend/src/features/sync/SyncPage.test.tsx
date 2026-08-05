import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { offlineDb, type DraftRecord } from '../../offline/db';
import { SyncPage } from './SyncPage';

const force = vi.fn();

vi.mock('../../offline/handlers', () => ({
  syncHandlers: {
    // Attendance has no force door on purpose: a man was present or he was not.
    ATTENDANCE: { send: vi.fn() },
    EXPENSE: { send: vi.fn(), force: (p: unknown, r: string) => force(p, r) },
  },
}));

const syncNow = vi.fn();

vi.mock('../../offline/SyncProvider', () => ({
  useSync: () => ({ online: true, syncing: false, lastSummary: null, syncNow }),
}));

function draft(overrides: Partial<DraftRecord> & Pick<DraftRecord, 'clientId'>): DraftRecord {
  return {
    entityType: 'EXPENSE',
    siteId: 'site-a',
    businessDate: '2026-08-05',
    payload: { id: overrides.clientId, description: 'Cartage' },
    status: 'PENDING',
    attempts: 0,
    nextAttemptAt: 0,
    summary: 'Cartage — KSN-A Kausani',
    clientUpdatedAt: '2026-08-05T09:00:00.000Z',
    createdAt: '2026-08-05T09:00:00.000Z',
    ...overrides,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SyncPage />
    </MemoryRouter>,
  );
}

describe('SyncPage', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    force.mockResolvedValue(undefined);
    await offlineDb.drafts.clear();
    await offlineDb.uploads.clear();
  });

  it('says so plainly when the device owes the server nothing', async () => {
    renderPage();

    expect(
      await screen.findByText('Everything on this device has reached the server.'),
    ).toBeVisible();
  });

  /**
   * The reassurance half. A record merely waiting needs nothing from anybody, and the screen
   * saying so is the whole point — a supervisor who cannot see his morning's work anywhere
   * assumes the app ate it.
   */
  it('names what is waiting rather than showing a count', async () => {
    await offlineDb.drafts.put(draft({ clientId: 'e-1' }));

    renderPage();

    expect(await screen.findByText('Cartage — KSN-A Kausani')).toBeVisible();
    expect(screen.getByText('Pending sync')).toBeVisible();
    // Nothing to decide, so nothing is asked.
    expect(screen.queryByRole('button', { name: 'Decide' })).not.toBeInTheDocument();
  });

  /**
   * The phase's second exit criterion: <b>a conflict surfaces a resolvable prompt.</b> Not an
   * error message and not a silent retry — a question, with the two answers a person can
   * actually give from a phone.
   */
  it('offers a conflict as a decision with a reason, and books it when the reason is given', async () => {
    await offlineDb.drafts.put(
      draft({
        clientId: 'e-2',
        status: 'CONFLICT',
        attempts: 1,
        lastError: 'This looks like an expense already on file.',
      }),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Decide' }));
    // Scoped to the dialog: the same sentence is on the row behind it, which is the point —
    // the list says what is wrong and the dialog asks what to do about it.
    const dialog = within(await screen.findByRole('dialog'));
    expect(dialog.getByText('This looks like an expense already on file.')).toBeVisible();

    // Keeping this device's version is refused until there is a reason to put on the record.
    const keep = screen.getByRole('button', { name: 'Keep mine' });
    expect(keep).toBeDisabled();

    await user.type(
      screen.getByLabelText('If it really is separate, say why'),
      'Second lorry the same afternoon',
    );
    expect(keep).toBeEnabled();
    await user.click(keep);

    await waitFor(() => {
      expect(force).toHaveBeenCalledWith(
        { id: 'e-2', description: 'Cartage' },
        'Second lorry the same afternoon',
      );
    });
    await waitFor(async () => {
      expect((await offlineDb.drafts.get('e-2'))?.status).toBe('SYNCED');
    });
  });

  /**
   * The honest asymmetry. Where the server has no door, the dialog says so instead of
   * offering a button that would collide again — and leaves discarding as the way out.
   */
  it('does not offer to force a conflict the server would refuse again', async () => {
    await offlineDb.drafts.put(
      draft({
        clientId: 'a-1',
        entityType: 'ATTENDANCE',
        status: 'CONFLICT',
        summary: '12 attendance mark(s) — KSN-A Kausani',
        lastError: 'Naresh is already marked for 2026-08-05.',
      }),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Decide' }));

    expect(screen.queryByRole('button', { name: 'Keep mine' })).not.toBeInTheDocument();
    expect(screen.getByText(/cannot be forced through/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Discard mine' })).toBeVisible();
  });

  it('drops this device version and its photographs when the server version wins', async () => {
    await offlineDb.drafts.put(draft({ clientId: 'e-3', status: 'CONFLICT' }));
    await offlineDb.uploads.put({
      uploadId: 'u-1',
      clientId: 'e-3',
      entityType: 'EXPENSE',
      fileName: 'bill.jpg',
      contentType: 'image/jpeg',
      blob: new Blob(['x']),
      originalBytes: 4_000_000,
      status: 'PENDING',
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: '2026-08-05T09:00:00.000Z',
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Decide' }));
    await user.click(screen.getByRole('button', { name: 'Discard mine' }));

    await waitFor(async () => {
      expect(await offlineDb.drafts.count()).toBe(0);
      expect(await offlineDb.uploads.count()).toBe(0);
    });
  });

  it('lets a failed record be tried again by hand', async () => {
    await offlineDb.drafts.put(
      draft({
        clientId: 'e-4',
        status: 'FAILED',
        attempts: 8,
        nextAttemptAt: 999_999,
        lastError: 'The server is restarting. Given up after 8 tries.',
      }),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Try again' }));

    await waitFor(async () => {
      const row = await offlineDb.drafts.get('e-4');
      expect(row?.status).toBe('PENDING');
      expect(row?.attempts).toBe(0);
      expect(row?.nextAttemptAt).toBe(0);
    });
  });
});

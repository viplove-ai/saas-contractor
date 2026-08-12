import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkersPage } from './WorkersPage';
import type { PageResponse, SiteDirectoryEntry, Worker } from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const del = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      post: (...args: unknown[]) => post(...args),
      put: (...args: unknown[]) => put(...args),
      delete: (...args: unknown[]) => del(...args),
    },
  };
});

/** A supervisor: he may take men on and move them, but not decide what they are paid. */
let permissions = ['worker:read', 'worker:write', 'attendance:create'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-sup', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

/** Vivek supervises KSN-A only; KSN-B is another supervisor's site. */
const MY_SITES = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const DIRECTORY: SiteDirectoryEntry[] = [
  { id: 'site-a', projectId: 'p1', code: 'KSN-A', name: 'Kausani Main Block', status: 'ACTIVE' },
  { id: 'site-b', projectId: 'p1', code: 'KSN-B', name: 'Kausani Annexe', status: 'ACTIVE' },
  { id: 'site-c', projectId: 'p2', code: 'BAG-A', name: 'Bageshwar Road', status: 'ACTIVE' },
];

const WORKERS: PageResponse<Worker> = {
  content: [
    {
      id: 'w1',
      workerCode: 'W-101',
      fullName: 'Karam Singh',
      mobile: '9800000101',
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      active: true,
      currentSiteId: 'site-a',
      // Never on the edit form, and the point of the pass-through test below.
      aadhaarLast4: '4321',
      bankAccountNo: '11223344',
      bankIfsc: 'SBIN0001234',
      bankName: 'State Bank of India',
      version: 3,
      currentWageRate: {
        id: 'r1',
        workerId: 'w1',
        normalRate: 625,
        overtimeRate: 89.28,
        effectiveFrom: '2024-12-01',
      },
    },
    {
      id: 'w2',
      workerCode: 'W-108',
      fullName: 'Naya Mazdoor',
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      active: true,
      currentSiteId: 'site-a',
      version: 0,
    },
  ],
  page: 0,
  size: 200,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/*
  The register draws a table and a card list at once and hides one by breakpoint, so every
  worker's name is in the document twice. Scope to the layout under test, the same way the
  projects register's tests do.
*/
const table = () => within(screen.getByRole('table', { name: 'Workers' }));
const cards = () => within(screen.getByRole('list', { name: 'Workers' }));

describe('WorkersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['worker:read', 'worker:write', 'attendance:create'];
    get.mockImplementation((url: string) => {
      if (url === '/workers') return Promise.resolve({ data: WORKERS });
      if (url === '/sites') return Promise.resolve({ data: MY_SITES });
      if (url === '/sites/directory') return Promise.resolve({ data: DIRECTORY });
      if (url === '/skill-categories') return Promise.resolve({ data: [] });
      if (url === '/labour-contractors')
        return Promise.resolve({ data: { ...WORKERS, content: [] } });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
    del.mockResolvedValue({ data: {} });
  });

  // The button was disabled with nothing said, and a supervisor with no posting read that
  // as the app being broken. He is told what is missing and who fixes it.
  it('says why a man cannot be taken on when the supervisor is posted nowhere', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/workers') return Promise.resolve({ data: { ...WORKERS, content: [] } });
      if (url === '/sites') return Promise.resolve({ data: [] });
      if (url === '/sites/directory') return Promise.resolve({ data: DIRECTORY });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    expect(await screen.findByText(/You are not posted to a site yet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take on a worker' })).toBeDisabled();
  });

  it('says plainly when the office has not set a rate yet', async () => {
    renderPage();
    await screen.findAllByText('Naya Mazdoor');
    const unpaid = table().getByText('Naya Mazdoor').closest('tr') as HTMLElement;
    const paid = table().getByText('Karam Singh').closest('tr') as HTMLElement;

    expect(within(unpaid).getByText('Not set by office')).toBeInTheDocument();
    expect(within(paid).getByText(/625/)).toBeInTheDocument();

    // And the same two facts on the card the phone gets.
    const unpaidCard = cards().getByText('Naya Mazdoor').closest('li') as HTMLElement;
    expect(within(unpaidCard).getByText('Not set by office')).toBeInTheDocument();
  });

  it('onboards a man onto the supervisor’s own site without touching pay', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Take on a worker' }));
    await user.type(await screen.findByLabelText('Full name'), 'Bhola Ram');
    await user.type(screen.getByLabelText('Mobile'), '9800000123');
    // Nobody is asked to invent a worker number any more; the server assigns it.
    expect(screen.queryByLabelText('Worker number')).not.toBeInTheDocument();
    // Nor for anything the office fills in later.
    expect(screen.queryByLabelText('Engaged as')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Joining date')).not.toBeInTheDocument();
    // No pay field at all for someone who cannot set pay.
    expect(screen.queryByLabelText('Wage a day (optional)')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add worker' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { fullName: string; mobile: string; siteId: string; normalRate?: number },
    ];
    expect(url).toBe('/workers');
    expect(body.fullName).toBe('Bhola Ram');
    expect(body.mobile).toBe('9800000123');
    expect(body.siteId).toBe('site-a');
    expect(body.normalRate).toBeUndefined();
  });

  // Mandatory because a man nobody can telephone is a man nobody can call back to the site.
  it('will not take a man on without a mobile number', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Take on a worker' }));
    await user.type(await screen.findByLabelText('Full name'), 'Bhola Ram');
    await user.click(screen.getByRole('button', { name: 'Add worker' }));

    expect(await screen.findByText('Enter his mobile number')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it('offers the day’s wage to someone who may set pay, and asks no overtime rate', async () => {
    permissions = ['worker:read', 'worker:write', 'wage:write'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Take on a worker' }));
    expect(await screen.findByLabelText('Wage a day (optional)')).toBeInTheDocument();
    // The site already knows where its working hours end; the hour past them is derived.
    expect(screen.queryByLabelText('Overtime rate an hour (optional)')).not.toBeInTheDocument();
  });

  /*
    The update call replaces the row rather than patching it, so anything the form does not
    carry back is cleared. Correcting a mobile number used to be a way to wipe a man's bank
    details, which nobody would notice until he was not paid.
  */
  it('corrects a worker and carries his untouched particulars back unchanged', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));

    const mobile = await screen.findByLabelText('Mobile');
    expect(mobile).toHaveValue('9800000101');
    await user.clear(mobile);
    await user.type(mobile, '9800000999');
    // Pay is revised, never edited, so it has no business on a form that overwrites.
    expect(screen.queryByLabelText('Wage a day (optional)')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [
      string,
      { mobile: string; bankAccountNo?: string; aadhaarLast4?: string; version: number },
    ];
    expect(url).toBe('/workers/w1');
    expect(body.mobile).toBe('9800000999');
    expect(body.bankAccountNo).toBe('11223344');
    expect(body.aadhaarLast4).toBe('4321');
    // The version read with the row, so a stale edit is a 409 rather than a silent overwrite.
    expect(body.version).toBe(3);
  });

  // Marking him Left is the ordinary end of a man's time here; deletion is for a row that
  // should not exist. Without a last day the first of those has no date to be asked about.
  it('will not mark a man Left without his last day', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));

    await user.click(await screen.findByRole('combobox', { name: 'Status' }));
    await user.click(screen.getByRole('option', { name: 'Left' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Give his last day')).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  it('offers the supervisor no way to delete a man', async () => {
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;

    expect(within(row).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('lets an engineer delete a man, and makes him say why', async () => {
    permissions = ['worker:read', 'worker:write', 'worker:delete'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete' }));

    // The reason is the only record of the decision, so it is the only way past the dialog.
    await user.click(await screen.findByRole('button', { name: 'Delete worker' }));
    expect(await screen.findByText(/Say why this is being deleted/)).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();

    await user.type(
      screen.getByLabelText(/Why is it being deleted/),
      'Entered twice at the gate on 3 March',
    );
    await user.click(screen.getByRole('button', { name: 'Delete worker' }));

    await waitFor(() => expect(del).toHaveBeenCalledOnce());
    const [url, config] = del.mock.calls[0] as [string, { data: { reason: string } }];
    expect(url).toBe('/workers/w1');
    expect(config.data.reason).toBe('Entered twice at the gate on 3 March');
  });

  // The refusal carries the count of what is recorded against him, which is the fact that
  // changes the engineer's mind — so it has to reach the screen, not a swallowed console.
  it('shows the server’s refusal when the man has already worked', async () => {
    permissions = ['worker:read', 'worker:write', 'worker:delete'];
    del.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          detail:
            'This worker has 12 attendance records recorded against him. A man who has worked cannot be taken off the books — mark him Left instead, which stops him appearing on the roll and keeps his figures.',
        },
      },
    });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete' }));

    await user.type(screen.getByLabelText(/Why is it being deleted/), 'Duplicate');
    await user.click(screen.getByRole('button', { name: 'Delete worker' }));

    expect(await screen.findByText(/12 attendance records/)).toBeInTheDocument();
    // And it stays open on the row, so "mark him Left instead" is one click away.
    expect(screen.getByRole('button', { name: 'Delete worker' })).toBeInTheDocument();
  });

  it('transfers to a site the supervisor does not work at, on any project', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');
    const row = table().getByText('Karam Singh').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Transfer' }));

    await user.click(await screen.findByRole('combobox', { name: 'Send him to' }));
    const options = (await screen.findAllByRole('option')).map((option) => option.textContent);
    // The whole directory less where he already is — including the other project's site.
    expect(options).toEqual(['KSN-B — Kausani Annexe', 'BAG-A — Bageshwar Road']);

    await user.click(screen.getByRole('option', { name: /KSN-B/ }));
    await user.click(screen.getByRole('button', { name: 'Transfer' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { siteId: string; effectiveFrom: string }];
    expect(url).toBe('/workers/w1/allocations');
    expect(body.siteId).toBe('site-b');
    // Tomorrow by default: a posting cannot be closed before the day it opened.
    expect(new Date(body.effectiveFrom).getTime()).toBeGreaterThan(Date.now());
  });
});

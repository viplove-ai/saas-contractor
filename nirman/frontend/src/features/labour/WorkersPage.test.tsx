import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkersPage } from './WorkersPage';
import type { PageResponse, SiteDirectoryEntry, Worker } from './types';

const get = vi.fn();
const post = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      post: (...args: unknown[]) => post(...args),
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
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      active: true,
      currentSiteId: 'site-a',
      currentWageRate: {
        id: 'r1',
        workerId: 'w1',
        normalRate: 625,
        overtimeRate: 89.28,
        effectiveFrom: '2024-12-01',
      },
      version: 0,
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
    await user.type(screen.getByLabelText('Worker number'), 'W-109');
    // No rate fields at all for someone who cannot set pay.
    expect(screen.queryByLabelText('Rate (optional)')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add worker' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { fullName: string; siteId: string; normalRate?: number },
    ];
    expect(url).toBe('/workers');
    expect(body.fullName).toBe('Bhola Ram');
    expect(body.siteId).toBe('site-a');
    expect(body.normalRate).toBeUndefined();
  });

  it('offers the pay fields to someone who may set pay', async () => {
    permissions = ['worker:read', 'worker:write', 'wage:write'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Take on a worker' }));
    expect(await screen.findByLabelText('Rate (optional)')).toBeInTheDocument();
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

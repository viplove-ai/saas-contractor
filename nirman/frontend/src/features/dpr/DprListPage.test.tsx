import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DprListPage } from './DprListPage';
import type { Dpr, PageResponse, Site } from './types';

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

/** An engineer signs reports. A supervisor writes them and does not. */
let permissions = ['dpr:draft', 'dpr:verify'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-eng', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

function dpr(overrides: Partial<Dpr> & { id: string; dprNumber: string }): Dpr {
  return {
    siteId: 'site-a',
    siteName: 'Kausani Main Block',
    projectId: 'p1',
    reportDate: '2025-06-10',
    workflowStatus: 'SUBMITTED',
    snapshotFrozen: true,
    labourPresentCount: 3,
    labourRegularHours: 21,
    labourOvertimeHours: 1,
    labourCost: 1875,
    materialConsumedValue: 3200,
    expenseAmount: 1800,
    dayCost: 6875,
    version: 1,
    workItems: [],
    labour: [],
    machinery: [],
    photos: [],
    ...overrides,
  };
}

const SUBMITTED = dpr({
  id: 'd1',
  dprNumber: 'DPR-2025-9002',
  workItems: [
    {
      id: 'w1',
      boqItemId: 'boq-brick',
      itemNumber: 'B-003',
      activity: 'Brickwork 1:4',
      workLocation: 'Ground floor',
      quantity: 12.5,
      sortOrder: 0,
      measured: true,
    },
    {
      id: 'w2',
      activity: 'Shuttering struck and stacked',
      sortOrder: 1,
      measured: false,
    },
  ],
});

function page(content: Dpr[]): PageResponse<Dpr> {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DprListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(detail: Dpr = SUBMITTED, listed: Dpr[] = [SUBMITTED]) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/dprs') return Promise.resolve({ data: page(listed) });
    if (url === '/dprs/d1') return Promise.resolve({ data: detail });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('DprListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['dpr:draft', 'dpr:verify'];
    mockGets();
    post.mockResolvedValue({ data: { ...SUBMITTED, workflowStatus: 'VERIFIED' } });
  });

  it('lists reports with their status and day cost', async () => {
    renderPage();

    expect(await screen.findByText('DPR-2025-9002')).toBeInTheDocument();
    expect(screen.getByText('Submitted')).toBeInTheDocument();
  });

  /**
   * Verifying is what claims work against the contract, so the engineer sees the measured
   * lines before he sees the button — and the button says how many he is about to claim.
   */
  it('shows what would be claimed before offering to verify', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));

    expect(await screen.findByText('Claimed against the contract')).toBeInTheDocument();
    expect(screen.getByText('B-003')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Verify and claim 1 line(s)' }),
    ).toBeInTheDocument();
  });

  /** A row that describes work without measuring it claims nothing, and is shown apart. */
  it('separates work recorded from work claimed', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));

    expect(await screen.findByText('Recorded, not claimed')).toBeInTheDocument();
    expect(screen.getByText('Shuttering struck and stacked')).toBeInTheDocument();
  });

  /** The frozen snapshot is explained on the panel, so a difference reads as intent. */
  it('explains that a submitted report no longer follows the records', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));

    expect(await screen.findByText(/frozen when the report was sent/)).toBeInTheDocument();
  });

  it('sends the verification', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));
    await user.click(await screen.findByRole('button', { name: 'Verify and claim 1 line(s)' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/dprs/d1/verify');
    expect(post.mock.calls[0]![1]).toMatchObject({ action: 'VERIFY' });
  });

  /** Sending a report back without a reason leaves the supervisor guessing. */
  it('will not send a report back without a reason', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));
    await user.click(await screen.findByRole('button', { name: 'Send back' }));

    const confirm = screen.getAllByRole('button', { name: 'Send back' }).at(-1)!;
    expect(confirm).toBeDisabled();

    await user.type(
      screen.getByRole('textbox', { name: 'What needs fixing' }),
      'Quantity does not match the measurement sheet',
    );
    await user.click(screen.getAllByRole('button', { name: 'Send back' }).at(-1)!);

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({
      action: 'REJECT',
      remarks: 'Quantity does not match the measurement sheet',
    });
  });

  /** Signing is the engineer's act. A supervisor gets the report and no decision. */
  it('offers no decision to somebody who cannot verify', async () => {
    permissions = ['dpr:draft'];
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));
    await screen.findByText('Claimed against the contract');

    expect(screen.queryByRole('button', { name: /^Verify/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send back' })).not.toBeInTheDocument();
  });

  /**
   * The way back into an unfinished report. Without it the list can only be read, and a draft
   * started at lunchtime is a draft nobody can finish.
   */
  it('offers a way back into a draft and not into a signed report', async () => {
    const draft = dpr({ id: 'd2', dprNumber: 'DPR-2025-9003', workflowStatus: 'DRAFT' });
    mockGets(SUBMITTED, [SUBMITTED, draft]);
    renderPage();

    await screen.findByText('DPR-2025-9003');
    const links = screen.getAllByRole('link', { name: 'Continue' });
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/dpr/d2');
  });

  /** A verified report reports what it posted, so the claim is visible after the fact. */
  it('names how many claims a verified report posted', async () => {
    mockGets(
      dpr({
        id: 'd1',
        dprNumber: 'DPR-2025-9002',
        workflowStatus: 'VERIFIED',
        verifiedByName: 'Uttam Rana',
        progressPosted: 1,
      }),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText('DPR-2025-9002'));

    expect(
      await screen.findByText(/1 claim\(s\) posted to the measurement book/),
    ).toBeInTheDocument();
  });
});

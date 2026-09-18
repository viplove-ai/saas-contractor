import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse, Settlement, Worker, WorkerAdvance, WorkerPayment } from './types';
import { WorkerAdvancesPage } from './WorkerAdvancesPage';

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

/** The administrator: records, decides and pays. Narrowed per test. */
let permissions = [
  'worker:read',
  'wage:read',
  'worker:advance',
  'advance:settle:approve',
  'payment:record',
];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

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
      currentSiteIds: ['site-a'],
      version: 1,
    },
    {
      id: 'w2',
      workerCode: 'W-102',
      fullName: 'Ramesh Bisht',
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      active: true,
      currentSiteId: 'site-a',
      currentSiteIds: ['site-a'],
      version: 1,
    },
  ],
  page: 0,
  size: 200,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
};

/**
 * Three advances, one in each state the screen has to tell apart: waiting on the office,
 * approved and still to come out of wages, and approved but borne by the firm.
 */
const ADVANCES: WorkerAdvance[] = [
  {
    id: 'adv-waiting',
    advanceNumber: 'ADV-2025-0003',
    siteId: 'site-a',
    workerId: 'w2',
    workerName: 'Ramesh Bisht',
    advanceDate: '2025-09-12',
    amount: 1500,
    paymentMode: 'CASH',
    purpose: 'Diwali',
    recoverable: true,
    recoveredAmount: 0,
    balanceAmount: 1500,
    status: 'OPEN',
    workflowStatus: 'DRAFT',
    version: 0,
  },
  {
    id: 'adv-open',
    advanceNumber: 'ADV-2025-0001',
    siteId: 'site-a',
    workerId: 'w1',
    workerName: 'Karam Singh',
    advanceDate: '2025-09-03',
    amount: 2000,
    paymentMode: 'CASH',
    purpose: 'Cash advance',
    recoverable: true,
    recoveredAmount: 0,
    balanceAmount: 2000,
    status: 'OPEN',
    workflowStatus: 'APPROVED',
    approvedAt: '2025-09-03T10:00:00Z',
    version: 1,
  },
  {
    id: 'adv-borne',
    advanceNumber: 'ADV-2025-0002',
    siteId: 'site-a',
    workerId: 'w1',
    workerName: 'Karam Singh',
    advanceDate: '2025-09-04',
    amount: 800,
    paymentMode: 'GOODS',
    purpose: 'Footwear',
    recoverable: false,
    recoveredAmount: 0,
    balanceAmount: 800,
    status: 'OPEN',
    workflowStatus: 'APPROVED',
    approvedAt: '2025-09-04T10:00:00Z',
    version: 1,
  },
];

const PAYMENTS: WorkerPayment[] = [
  {
    id: 'pay-1',
    paymentNumber: 'WPY-2025-0001',
    siteId: 'site-a',
    workerId: 'w2',
    workerName: 'Ramesh Bisht',
    paymentDate: '2025-08-31',
    amount: 9000,
    paymentMode: 'CASH',
    advancesRecovered: 1000,
    version: 0,
  },
];

/** Karam: 24 days at 625, drew 2,000, nothing paid. Owed 13,000. */
const KARAM_SHEET: Settlement = {
  workerId: 'w1',
  workerCode: 'W-101',
  workerName: 'Karam Singh',
  earnedAmount: 15000,
  advanceAmount: 2000,
  openAdvanceAmount: 2000,
  paidAmount: 0,
  deductionAmount: 0,
  netPayable: 13000,
  entries: [
    {
      id: 'e1',
      entryDate: '2025-09-01',
      periodYearMonth: '2025-09',
      entryType: 'WAGE_EARNED',
      direction: 1,
      amount: 625,
      balanceAfter: 625,
      sourceType: 'ATTENDANCE',
    },
    {
      id: 'e2',
      entryDate: '2025-09-03',
      periodYearMonth: '2025-09',
      entryType: 'ADVANCE',
      direction: -1,
      amount: 2000,
      balanceAfter: -1375,
      sourceType: 'WORKER_ADVANCE',
      reason: 'Cash advance',
    },
  ],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkerAdvancesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets() {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/workers') return Promise.resolve({ data: WORKERS });
    if (url === '/worker-advances') return Promise.resolve({ data: { content: ADVANCES } });
    if (url === '/worker-payments') return Promise.resolve({ data: { content: PAYMENTS } });
    if (url === '/workers/w1/settlement') return Promise.resolve({ data: KARAM_SHEET });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('WorkerAdvancesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = [
      'worker:read',
      'wage:read',
      'worker:advance',
      'advance:settle:approve',
      'payment:record',
    ];
    mockGets();
    post.mockResolvedValue({ data: { id: 'new' } });
    localStorage.clear();
  });

  /**
   * The queue is drawn first and apart. An advance nobody has decided on comes off nobody's
   * wages, and a queue that has to be found in a list is a queue nobody clears.
   */
  it('puts the advances waiting on a decision above the rest', async () => {
    renderPage();

    const waiting = await screen.findByRole('heading', { name: 'Waiting on a decision' });
    expect(waiting).toBeInTheDocument();
    // RecordTable draws the table and the phone cards together, so every value is in the
    // document twice by design.
    expect(screen.getAllByText('Ramesh Bisht').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Diwali').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: 'Approve' }).length).toBeGreaterThan(0);
  });

  /**
   * Two figures, never netted: what is still to come out of wages and what has been paid
   * out are two different questions. Only an approved, recoverable advance counts towards
   * the first — the footwear the firm bears and the one still waiting both stay out of it.
   */
  it('counts only approved recoverable advances as still to come out of wages', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    const open = screen.getByText('Still to come out of wages').parentElement!;
    const paid = screen.getByText('Paid out at this site').parentElement!;
    expect(within(open).getByText('₹2,000.00')).toBeInTheDocument();
    expect(within(paid).getByText('₹9,000.00')).toBeInTheDocument();
  });

  it('says which advances the firm bears rather than the man', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    expect(screen.getAllByText('Borne by the firm').length).toBeGreaterThan(0);
  });

  /** Approving is what deducts it; the click goes straight to the decision endpoint. */
  it('approves a waiting advance', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    await user.click(screen.getAllByRole('button', { name: 'Approve' })[0]!);

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/worker-advances/adv-waiting/decision', {
        action: 'APPROVE',
        remarks: undefined,
      }),
    );
  });

  /**
   * The payday offers the whole of what he is owed and says what it will close. The office
   * counting out ₹13,000 is told the ₹2,000 he drew is what made it ₹13,000 and not ₹15,000.
   */
  it('offers the balance owed and says which advances the payday closes', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    await user.click(screen.getByRole('button', { name: 'Pay wages' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Karam Singh/ }));

    expect(
      await screen.findByText(
        'He is owed ₹13,000.00. Paying him closes ₹2,000.00 of advances his wages have covered.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('How much')).toHaveValue(13000);

    await user.click(screen.getByRole('button', { name: 'Pay him' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/worker-payments',
        expect.objectContaining({
          siteId: 'site-a',
          workerId: 'w1',
          amount: 13000,
          paymentMode: 'CASH',
        }),
      ),
    );
  });

  /** The server's refusal is shown in its own words rather than pre-empted on the screen. */
  it('shows the refusal when the office types past what he is owed', async () => {
    const user = userEvent.setup();
    post.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 422,
        data: {
          detail:
            'He is owed ₹13000.00. Anything beyond that is an advance against wages not yet earned — record it as one.',
        },
      },
    });
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    await user.click(screen.getByRole('button', { name: 'Pay wages' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Karam Singh/ }));
    await screen.findByText(/He is owed ₹13,000.00/);
    await user.clear(screen.getByLabelText('How much'));
    await user.type(screen.getByLabelText('How much'), '14000');
    await user.click(screen.getByRole('button', { name: 'Pay him' }));

    expect(await screen.findByText(/Anything beyond that is an advance/)).toBeInTheDocument();
  });

  /** The sheet: the identity set out as the field sheet sets it out, and the lines under it. */
  it('opens a man’s account with what he earned, drew and is owed', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    // Karam's row on the decided register — the queue above it is Ramesh's.
    const register = screen.getByRole('table', { name: 'Advances' });
    const karam = within(register)
      .getAllByRole('row')
      .find((row) => within(row).queryByText('Karam Singh'))!;
    await user.click(within(karam).getByRole('button', { name: 'His account' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Karam Singh — W-101')).toBeInTheDocument();
    expect(within(dialog).getByText('Owed to him').parentElement!).toHaveTextContent('₹13,000.00');
    expect(within(dialog).getByText('₹2,000.00 still open')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/Cash advance/).length).toBeGreaterThan(0);
  });

  /**
   * The supervisor records the hand-over and nothing else: the ration went out of his hand
   * at the gate, and whether it comes out of the man's wages is somebody else's decision.
   */
  it('lets a supervisor record an advance but neither approve nor pay', async () => {
    permissions = ['worker:read', 'wage:read', 'worker:advance'];
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    expect(screen.queryByRole('button', { name: 'Pay wages' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Record an advance' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Karam Singh/ }));
    await user.type(screen.getByLabelText('How much'), '500');
    await user.type(screen.getByLabelText('What for'), 'Ration');
    await user.click(screen.getByRole('button', { name: 'Record it' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/worker-advances',
        expect.objectContaining({
          siteId: 'site-a',
          workerId: 'w1',
          amount: 500,
          purpose: 'Ration',
          recoverable: true,
          id: expect.any(String),
        }),
      ),
    );
  });

  /** An account with none of the three keys reads the register and acts on nothing. */
  it('hides the buttons from an account that holds none of the three permissions', async () => {
    permissions = ['worker:read', 'wage:read'];
    renderPage();
    await screen.findByRole('heading', { name: 'Waiting on a decision' });

    expect(screen.queryByRole('button', { name: 'Record an advance' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pay wages' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });
});

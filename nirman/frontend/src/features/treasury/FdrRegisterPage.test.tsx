import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FdrRegisterPage } from './FdrRegisterPage';
import type { BankDeposit, DepositRegister } from './types';

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

const permissions = ['security:read', 'security:write'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-1', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

function deposit(overrides: Partial<BankDeposit> = {}): BankDeposit {
  return {
    id: 'fd-1',
    depositNumber: 'FDR/9912',
    bankName: 'SBI',
    branch: 'Kausani',
    amount: 500000,
    issuedOn: '2026-01-10',
    maturityOn: '2027-01-10',
    interestRate: 6.75,
    status: 'HELD',
    history: [],
    photos: [],
    daysToMaturity: 145,
    version: 0,
    ...overrides,
  };
}

/**
 * Two certificates: one pledged to a contract, one in hand. The second is the whole point of the
 * register — money available for the next tender, which the per-contract view cannot report.
 */
function register(overrides: Partial<DepositRegister> = {}): DepositRegister {
  return {
    deposits: [
      deposit({
        id: 'fd-1',
        depositNumber: 'FDR/9912',
        pledgedTo: {
          securityId: 'sec-1',
          projectId: 'p1',
          projectCode: 'KSN01',
          projectName: 'Kausani Guest House',
          securityType: 'PERFORMANCE_GUARANTEE',
          status: 'LODGED',
          lodgedOn: '2026-01-12',
        },
        history: [
          {
            securityId: 'sec-0',
            projectId: 'p0',
            projectCode: 'PTH01',
            projectName: 'Pithoragarh',
            securityType: 'EMD',
            status: 'RELEASED',
            lodgedOn: '2025-06-01',
            releasedOn: '2025-08-01',
          },
          {
            securityId: 'sec-1',
            projectId: 'p1',
            projectCode: 'KSN01',
            projectName: 'Kausani Guest House',
            securityType: 'PERFORMANCE_GUARANTEE',
            status: 'LODGED',
            lodgedOn: '2026-01-12',
          },
        ],
      }),
      deposit({ id: 'fd-2', depositNumber: 'FDR/1044', amount: 250000, version: 3 }),
    ],
    summary: {
      heldCount: 2,
      heldAmount: 750000,
      pledgedCount: 1,
      pledgedAmount: 500000,
      idleCount: 1,
      idleAmount: 250000,
      closedCount: 0,
      maturingSoonCount: 0,
    },
    ...overrides,
  };
}

/**
 * Scoped to the table layout. {@link RecordTable} draws the register twice — a table for a desk
 * and a card per row for a hand — and hides one of them in CSS, so an unscoped query finds every
 * label twice.
 */
const table = () => within(screen.getByRole('table'));

function renderPage(data: DepositRegister = register()) {
  get.mockImplementation((url: string) => {
    if (url === '/bank-deposits') {
      return Promise.resolve({ data });
    }
    return Promise.resolve({ data: { url: 'blob:photo', fileName: 'fdr.jpg' } });
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <FdrRegisterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FdrRegisterPage', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  /** The figure the per-contract register could not produce at all. */
  it('leads with what is free to bid with, apart from what is pledged', async () => {
    renderPage();

    const idle = (await screen.findByText('Free to bid with')).closest('div') as HTMLElement;
    expect(within(idle).getByText('₹2,50,000.00')).toBeInTheDocument();

    const pledged = screen.getByText('Pledged to contracts').closest('div') as HTMLElement;
    expect(within(pledged).getByText('₹5,00,000.00')).toBeInTheDocument();
  });

  it('says which contract holds a certificate, and which held it before', async () => {
    renderPage();

    await screen.findAllByText('FDR/9912');
    expect(table().getByRole('link', { name: 'KSN01' })).toHaveAttribute(
      'href',
      '/projects/p1/securities',
    );
    // The thread: the same certificate did the earnest money on the earlier tender.
    expect(table().getByText('Was with PTH01')).toBeInTheDocument();
  });

  it('shows an unpledged certificate as in hand', async () => {
    renderPage();

    await screen.findAllByText('FDR/1044');
    const row = table().getByText('FDR/1044').closest('tr') as HTMLElement;
    // Two different statements, deliberately worded apart: the column says nobody holds it, the
    // chip says the company still does.
    expect(within(row).getByText('Not pledged')).toBeInTheDocument();
    expect(within(row).getByText('In hand')).toBeInTheDocument();
  });

  it('enters a new certificate', async () => {
    const user = userEvent.setup({ delay: null });
    post.mockResolvedValue({ data: deposit() });
    renderPage();
    await screen.findAllByText('FDR/9912');

    await user.click(screen.getByRole('button', { name: 'Enter a deposit' }));
    await user.type(screen.getByLabelText('Certificate number'), 'FDR/2201');
    await user.type(screen.getByLabelText('Bank'), 'PNB');
    await user.type(screen.getByLabelText('Amount'), '300000');
    await user.type(screen.getByLabelText('Issued on'), '2026-08-01');
    await user.click(screen.getByRole('button', { name: 'Enter deposit' }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    const [url, body] = post.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/bank-deposits');
    expect(body.depositNumber).toBe('FDR/2201');
    expect(body.amount).toBe(300000);
    // A blank maturity is unknown, not a date — the server must not be told it matures today.
    expect(body.maturityOn).toBeUndefined();
  });

  /**
   * The register refuses this on the server too. The dialog says so before anybody types a date,
   * because a certificate the register calls closed and a department is holding is the same money
   * counted two ways.
   */
  it('warns before closing a certificate a department still holds', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('FDR/9912');

    const row = table().getByText('FDR/9912').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Close' }));

    expect(await screen.findByText(/KSN01 still has this lodged/)).toBeInTheDocument();
  });

  it('closes a certificate that is in hand', async () => {
    const user = userEvent.setup({ delay: null });
    post.mockResolvedValue({ data: deposit({ status: 'CLOSED', closedOn: '2026-08-18' }) });
    renderPage();
    await screen.findAllByText('FDR/1044');

    const row = table().getByText('FDR/1044').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Close' }));
    await user.type(screen.getByLabelText('Closed on'), '2026-08-18');
    await user.click(screen.getByRole('button', { name: 'Close deposit' }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    const [url, body] = post.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/bank-deposits/fd-2/close');
    expect(body.closedOn).toBe('2026-08-18');
    expect(body.version).toBe(3);
  });

  it('shows a photographed certificate as a thumbnail one tap from readable', async () => {
    renderPage(
      register({
        deposits: [deposit({ photos: [{ attachmentId: 'att-1', caption: 'FDR front' }] })],
      }),
    );

    const thumbs = await screen.findAllByAltText('FDR front');
    expect(thumbs[0]).toHaveAttribute('src', 'blob:photo');
  });

  /**
   * The register answers what the company holds today. A certificate the bank has paid out is
   * history and stays off the screen until somebody asks for it by name.
   */
  it('keeps closed certificates off the register until they are asked for', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage(
      register({
        deposits: [
          deposit({ id: 'fd-1', depositNumber: 'FDR/9912' }),
          deposit({
            id: 'fd-9',
            depositNumber: 'FDR/0001',
            status: 'CLOSED',
            closedOn: '2026-03-01',
          }),
        ],
        summary: { ...register().summary, closedCount: 1 },
      }),
    );

    await screen.findAllByText('FDR/9912');
    expect(table().queryByText('FDR/0001')).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Closed (1)' }));
    expect(table().getByText('FDR/0001')).toBeInTheDocument();
  });

  it('offers nothing to change when the reader may only look', async () => {
    permissions.splice(permissions.indexOf('security:write'), 1);
    try {
      renderPage();
      await screen.findAllByText('FDR/9912');
      expect(screen.queryByRole('button', { name: 'Enter a deposit' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
    } finally {
      permissions.push('security:write');
    }
  });
});

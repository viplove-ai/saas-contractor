import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DepositsPage } from './DepositsPage';
import type { DepositRegister, DepositRow, Site } from './types';

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

let permissions = ['expense:read', 'payment:record'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-acc', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

function row(overrides: Partial<DepositRow> = {}): DepositRow {
  return {
    expenseId: 'e1',
    expenseNumber: 'EXP-2026-0031',
    siteId: 'site-a',
    expenseDate: '2026-02-11',
    description: 'Electricity connection',
    categoryName: 'Site Expenses',
    vendorName: 'UPCL',
    totalAmount: 18000,
    refundableAmount: 12000,
    refundedAmount: 0,
    writtenOffAmount: 0,
    outstandingAmount: 12000,
    overdue: false,
    status: 'OUTSTANDING',
    workflowStatus: 'APPROVED',
    settlements: [],
    ...overrides,
  };
}

function register(overrides: Partial<DepositRegister> = {}): DepositRegister {
  return {
    placed: 12000,
    received: 0,
    writtenOff: 0,
    outstanding: 12000,
    overdue: 0,
    depositCount: 1,
    openCount: 1,
    rows: [row()],
    ...overrides,
  };
}

function mockGets(data: DepositRegister = register()) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/deposits') return Promise.resolve({ data });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

function renderPage() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter>
        <DepositsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * The register of money placed and not back.
 *
 * <p>Two questions and one screen. What is still out there, and with whom — the figure the
 * whole feature exists for, because until it existed the only record of a few lakh in meter
 * securities and plant hire money was the memory of whoever paid it. And what happened to the
 * one paid last March, which is the same screen with the settled rows shown, because sending
 * that question to a second screen is how it stops being asked.</p>
 */
describe('DepositsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['expense:read', 'payment:record'];
    mockGets();
  });

  it('leads with what is still out there, and with whom', async () => {
    renderPage();

    // Waited on a row rather than on the words "still out there" — the filter offers those
    // as an option before anything has loaded, so waiting on them proves nothing.
    expect(await screen.findByText(/Electricity connection/)).toBeInTheDocument();
    expect(screen.getByText(/EXP-2026-0031/)).toBeInTheDocument();
    expect(screen.getAllByText('₹12,000.00').length).toBeGreaterThan(0);
  });

  /** A deposit past the date somebody expected it back is one nobody is chasing. */
  it('says out loud what was expected back and has not come', async () => {
    mockGets(
      register({
        overdue: 12000,
        rows: [row({ overdue: true, refundExpectedOn: '2026-06-30' })],
      }),
    );
    renderPage();

    expect(await screen.findByText(/was expected back before today/)).toBeInTheDocument();
    expect(screen.getByText('Overdue')).toBeInTheDocument();
  });

  it('records money coming back, and closes the deposit down to nothing', async () => {
    post.mockResolvedValue({ data: { id: 'r1' } });
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Settle' }));
    // Offered at the whole outstanding balance, because that is what usually comes back —
    // and typed over when the board refunds in two goes.
    expect(screen.getByRole('spinbutton', { name: 'Amount' })).toHaveValue(12000);

    await user.clear(screen.getByRole('spinbutton', { name: 'Amount' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Amount' }), '5000');
    await user.click(screen.getByRole('button', { name: 'Record it' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { outcome: string; amount: number; paymentMode?: string },
    ];
    expect(url).toBe('/deposits/e1/settlements');
    expect(body.outcome).toBe('RECEIVED');
    expect(body.amount).toBe(5000);
  });

  /** More than is outstanding is not this deposit coming back. */
  it('will not settle more than is still out on the bill', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Settle' }));
    await user.clear(screen.getByRole('spinbutton', { name: 'Amount' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Amount' }), '15000');

    expect(
      await screen.findByText(/Only ₹12,000\.00 is still out on this one/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record it' })).toBeDisabled();
  });

  /**
   * A deposit that vanishes from the register without a sentence cannot be asked about six
   * months later — which is the failure this whole screen exists to end.
   */
  it('demands a reason before giving up on a deposit', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Settle' }));
    await user.click(screen.getByRole('combobox', { name: 'What happened' }));
    await user.click(await screen.findByRole('option', { name: 'It is not coming back' }));

    expect(screen.getByRole('button', { name: 'Record it' })).toBeDisabled();
    expect(screen.getByText(/books no cost/)).toBeInTheDocument();

    await user.type(
      screen.getByRole('textbox', { name: 'Why is it not coming back' }),
      'Board says the meter was never surrendered',
    );

    expect(screen.getByRole('button', { name: 'Record it' })).toBeEnabled();
  });

  /**
   * Reading the register is the accountant's daily work and the supervisor's occasional one;
   * recording that money came back is the mirror of recording that money went out, and it is
   * held by the same permission.
   */
  it('offers no settling to somebody who cannot record a payment', async () => {
    permissions = ['expense:read'];
    renderPage();

    expect(await screen.findByText(/Electricity connection/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Settle' })).not.toBeInTheDocument();
  });

  it('shows what was done about a deposit once it is settled', async () => {
    mockGets(
      register({
        received: 12000,
        outstanding: 0,
        openCount: 0,
        rows: [
          row({
            refundedAmount: 12000,
            outstandingAmount: 0,
            status: 'SETTLED',
            settlements: [
              {
                id: 'r1',
                expenseId: 'e1',
                outcome: 'RECEIVED',
                settledOn: '2026-08-02',
                amount: 12000,
                paymentMode: 'BANK_TRANSFER',
                referenceNumber: '114552',
              },
            ],
          }),
        ],
      }),
    );
    renderPage();

    // The line is assembled from several text nodes, so it is read off the paragraph rather
    // than matched as one string.
    const settlement = (await screen.findAllByText(/came back/))
      .map((element) => element.textContent ?? '')
      .join(' ');
    // The stored code read as a person would say it — "bank_transfer" is not a word.
    expect(settlement).toContain('came back ₹12,000.00 by bank transfer · 114552');
    // And the row says so at a glance: the chip, plus the figure's own label.
    expect(screen.getAllByText('Come back').length).toBeGreaterThan(1);
  });
});

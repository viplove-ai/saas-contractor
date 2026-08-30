import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SiteFloatsPage } from './SiteFloatsPage';
import type { FloatBalance, FloatHolderOption, Site, SiteFloat } from './types';

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

/** The accountant: reads the register and hands the cash over. */
let permissions = ['expense:read', 'advance:issue'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-acc', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

/**
 * Two men: one carrying money, one out of pocket.
 *
 * <p>Both are in the fixture because the register's job is to keep those two facts apart. "We
 * have ₹12,000 out and we owe a man ₹4,000" is two things an accountant acts on separately —
 * cash to account for, and cash to hand over — and one net figure of ₹8,000 is neither.</p>
 */
const BALANCES: FloatBalance[] = [
  {
    userId: 'u-vivek',
    holderName: 'Vivek Aggarwal',
    siteId: 'site-a',
    issuedAmount: 20000,
    spentAmount: 8000,
    returnedAmount: 0,
    inHandAmount: 12000,
    openFloats: 1,
    oldestOpenOn: '2025-06-01',
  },
  {
    userId: 'u-ramesh',
    holderName: 'Ramesh Bisht',
    siteId: 'site-a',
    issuedAmount: 5000,
    spentAmount: 9000,
    returnedAmount: 0,
    inHandAmount: -4000,
    openFloats: 1,
  },
];

const FLOATS: SiteFloat[] = [
  {
    id: 'adv-1',
    advanceNumber: 'SAD-2025-0001',
    siteId: 'site-a',
    issuedToUserId: 'u-vivek',
    issuedToName: 'Vivek Aggarwal',
    advanceDate: '2025-06-01',
    amount: 20000,
    paymentMode: 'CASH',
    purpose: 'June petty cash',
    adjustedAmount: 8000,
    returnedAmount: 0,
    balanceAmount: 12000,
    settlementStatus: 'PARTIALLY_SETTLED',
    version: 1,
  },
  {
    id: 'adv-2',
    advanceNumber: 'SAD-2025-0002',
    siteId: 'site-a',
    issuedToUserId: 'u-ramesh',
    issuedToName: 'Ramesh Bisht',
    advanceDate: '2025-06-04',
    amount: 5000,
    paymentMode: 'UPI',
    purpose: 'Small tools',
    adjustedAmount: 9000,
    returnedAmount: 0,
    balanceAmount: -4000,
    settlementStatus: 'OVERSPENT',
    version: 2,
  },
];

const HOLDERS: FloatHolderOption[] = [
  { userId: 'u-vivek', username: 'vivek', fullName: 'Vivek Aggarwal', roleCodes: ['SUPERVISOR'] },
  { userId: 'u-ramesh', username: 'ramesh', fullName: 'Ramesh Bisht', roleCodes: ['SUPERVISOR'] },
  { userId: 'u-uttam', username: 'uttam', fullName: 'Uttam Rana', roleCodes: ['ENGINEER'] },
];

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SiteFloatsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets() {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/advances/float-balances') return Promise.resolve({ data: BALANCES });
    if (url === '/advances') return Promise.resolve({ data: { content: FLOATS } });
    if (url === '/advances/holders') return Promise.resolve({ data: HOLDERS });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('SiteFloatsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['expense:read', 'advance:issue'];
    mockGets();
    post.mockResolvedValue({ data: { id: 'adv-3' } });
    localStorage.clear();
  });

  /** The treasury's fourth question: where is the cash that is not in a bank or a department. */
  it('shows who is carrying the company money at this site', async () => {
    renderPage();

    // RecordTable draws the table and the phone cards together, one hidden by a breakpoint,
    // so every value on the register is in the document twice by design.
    expect(await screen.findAllByText('Vivek Aggarwal')).not.toHaveLength(0);
    expect(screen.getAllByText('Ramesh Bisht')).not.toHaveLength(0);
  });

  /**
   * Kept apart rather than netted. Cash to account for and cash to hand over are two different
   * acts, and a single net figure is neither of them.
   */
  it('keeps what is out with the staff apart from what is owed to them', async () => {
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    // Each tile carries its own figure. Never one netted 8,000, which is neither of them.
    const out = screen.getByText('Out with the staff').parentElement!;
    const owed = screen.getByText('Owed back to the staff').parentElement!;
    expect(within(out).getByText('₹12,000.00')).toBeInTheDocument();
    expect(within(owed).getByText('₹4,000.00')).toBeInTheDocument();
  });

  /**
   * A negative balance is said in words. "−4,000" on a cash register reads as a mistake to
   * anybody who has not been told otherwise, and the fact it carries — the company owes this
   * man money — is the one worth acting on.
   */
  it('says a negative balance is money owed to the man, not a minus sign', async () => {
    renderPage();
    await screen.findAllByText('Ramesh Bisht');

    expect(screen.getAllByText('owed to him').length).toBeGreaterThan(0);
    expect(screen.queryByText('-₹4,000.00')).not.toBeInTheDocument();
  });

  /** The balance is what the office argues about; the floats are what it argues from. */
  it('opens onto the floats behind one holder’s balance', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    await user.click(screen.getAllByRole('button', { name: 'Show the floats' })[0]!);

    expect(await screen.findAllByText('SAD-2025-0001')).not.toHaveLength(0);
    expect(screen.getAllByText('June petty cash')).not.toHaveLength(0);
  });

  /**
   * The person is picked off the site's own postings. The user list is behind an
   * administrator's permission, so an accountant could reach the call that hands over the money
   * and not the one that says who is standing there to take it.
   */
  it('offers the people posted to the site, with what each already holds', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    await user.click(screen.getByRole('button', { name: 'Hand over cash' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Vivek Aggarwal/ }));

    expect(await screen.findByText('He is already holding ₹12,000.00.')).toBeInTheDocument();
  });

  /**
   * Topping up a man who is owed money is a different act from topping up one who is carrying
   * some, and the dialog says which it is. The office deciding how much to hand over is
   * deciding it against what he already has, and making it ask a second screen is how somebody
   * ends up holding ₹40,000 nobody meant to give him.
   */
  it('warns when the person being handed cash is already owed money', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText('Ramesh Bisht');

    await user.click(screen.getByRole('button', { name: 'Hand over cash' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Ramesh Bisht/ }));

    expect(
      await screen.findByText(/He is already owed ₹4,000.00/),
    ).toBeInTheDocument();
  });

  /** Nothing to say about a man who has never been handed a float, so nothing is said. */
  it('says nothing about somebody who holds no float', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    await user.click(screen.getByRole('button', { name: 'Hand over cash' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Uttam Rana/ }));

    expect(screen.queryByText(/He is already/)).not.toBeInTheDocument();
  });

  it('hands the float over with its purpose', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    await user.click(screen.getByRole('button', { name: 'Hand over cash' }));
    await user.click(await screen.findByLabelText('To whom'));
    await user.click(await screen.findByRole('option', { name: /Vivek Aggarwal/ }));
    await user.type(screen.getByLabelText('How much'), '5000');
    await user.type(screen.getByLabelText('What it is for'), 'July petty cash');
    await user.click(screen.getByRole('button', { name: 'Hand it over' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/advances',
        expect.objectContaining({
          siteId: 'site-a',
          issuedToUserId: 'u-vivek',
          amount: 5000,
          purpose: 'July petty cash',
        }),
      ),
    );
  });

  /** A supervisor cannot hand himself petty cash. That separation is the whole control. */
  it('offers no way to hand cash over to somebody who cannot issue it', async () => {
    permissions = ['expense:read'];
    renderPage();
    await screen.findAllByText('Vivek Aggarwal');

    expect(screen.queryByRole('button', { name: 'Hand over cash' })).not.toBeInTheDocument();
  });

  it('says the register is the office’s to anybody who cannot read expenses', async () => {
    permissions = [];
    renderPage();

    expect(
      await screen.findByText(/Petty cash held at the sites is the office/),
    ).toBeInTheDocument();
  });
});

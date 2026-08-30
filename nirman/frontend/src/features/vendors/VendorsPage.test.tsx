import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { VendorDetailPage } from './VendorDetailPage';
import { VendorsPage } from './VendorsPage';
import type {
  PageResponse,
  SupplierEngagement,
  Vendor,
  VendorAccount,
  VendorPayment,
  VendorPurchase,
} from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();

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
    },
  };
});

let permissions = ['vendor:write', 'vendor:balance:manage', 'payment:record'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-acc', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const VENDORS: Vendor[] = [
  {
    id: 'v1',
    code: 'KSN-STEEL',
    name: 'Kausani Steel Traders',
    vendorType: 'MATERIAL',
    contactPerson: 'Harish Bisht',
    mobile: '9812345678',
    gstin: '05AABCU9603R1ZM',
    creditDays: 30,
    active: true,
    provisional: false,
    version: 0,
  },
];

function page<T>(content: T[]): PageResponse<T> {
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

/** Nothing billed, money already gone: the case a single net balance describes worst. */
const ACCOUNT: VendorAccount = {
  vendorId: 'v1',
  vendorCode: 'KSN-STEEL',
  vendorName: 'Kausani Steel Traders',
  openingBalance: 0,
  billedAmount: 0,
  paidAgainstBills: 0,
  advancePaid: 25000,
  outstanding: 0,
  netPosition: -25000,
  openBills: 0,
  purchasedValue: 41500,
  deliveryCount: 1,
};

const PURCHASES: VendorPurchase[] = [
  {
    receiptId: 'g9',
    grnNumber: 'GRN-2025-0009',
    receiptDate: '2025-06-02',
    invoiceNumber: 'SS/856',
    materialId: 'mat-cement',
    materialName: 'Cement OPC 43 Grade',
    unitCode: 'BAG',
    quantity: 100,
    rate: 415,
    amount: 41500,
    received: true,
  },
  {
    receiptId: 'g10',
    grnNumber: 'GRN-2025-0010',
    receiptDate: '2025-06-04',
    materialId: 'mat-steel',
    materialName: 'Steel TMT Fe-500D',
    unitCode: 'KG',
    quantity: 500,
    amount: 0,
    received: false,
  },
];

/** Two days his men were on site: one he attended, one he did not. */
const LABOUR: SupplierEngagement[] = [
  {
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Main Block',
    date: '2025-06-12',
    headCount: 11,
    manHours: 88,
    supplierPresent: true,
    representativeName: 'Karam Singh',
  },
  {
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Main Block',
    date: '2025-06-11',
    headCount: 9,
    supplierPresent: false,
  },
];

const PAYMENTS: VendorPayment[] = [
  {
    id: 'p1',
    paymentNumber: 'PAY-2025-0004',
    paymentDate: '2025-06-01',
    amount: 25000,
    paymentMode: 'BANK',
    referenceNumber: 'NEFT/9931',
  },
];

function renderRegister() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <VendorsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderAccount() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/vendors/v1']}>
        <Routes>
          <Route path="/vendors/:vendorId" element={<VendorDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('VendorsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['vendor:write', 'vendor:balance:manage', 'payment:record'];
    get.mockImplementation((url: string) => {
      if (url === '/vendors') return Promise.resolve({ data: page(VENDORS) });
      if (url === '/vendors/v1/account') return Promise.resolve({ data: ACCOUNT });
      if (url === '/vendors/v1/purchases') return Promise.resolve({ data: PURCHASES });
      if (url === '/payments') return Promise.resolve({ data: page(PAYMENTS) });
      if (url === '/vendors/v1/labour') return Promise.resolve({ data: [] });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
  });

  /**
   * The person and the number, in their own columns, and neither the GSTIN nor the credit
   * period. Both of those are read once a year by one person; the question the register is
   * actually opened with is who to ring about the lorry.
   */
  it('lists a supplier by who answers the phone and on what number', async () => {
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    const row = within(screen.getByRole('table')).getByText('KSN-STEEL').closest('tr')!;
    expect(within(row).getByText('Harish Bisht')).toBeInTheDocument();
    expect(within(row).getByText('9812345678')).toBeInTheDocument();
    expect(within(row).queryByText('05AABCU9603R1ZM')).not.toBeInTheDocument();
    expect(within(row).queryByText('30 days')).not.toBeInTheDocument();
  });

  /**
   * A name and a kind, and nothing else — the paperwork catches up later.
   *
   * <p>Not even a code. It was the first thing the form asked for and it was a filing
   * decision nobody was really making; the server derives it from what he supplies and what
   * he is called, which is also the only place uniqueness can be promised.</p>
   */
  it('onboards a supplier from a name alone, without asking for a code', async () => {
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    expect(screen.queryByLabelText('Short code')).not.toBeInTheDocument();
    await user.type(await screen.findByLabelText('Firm’s name'), 'Kosi Sand Suppliers');
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { code?: string; name: string; gstin?: string },
    ];
    expect(url).toBe('/vendors');
    expect(body.name).toBe('Kosi Sand Suppliers');
    expect(body.code).toBeUndefined();
    // An empty box is "not given", not a blank GSTIN stored as a GSTIN.
    expect(body.gstin).toBeUndefined();
  });

  /**
   * The supervisor's half of the same screen (V50).
   *
   * <p>He may say what the firm is called, what it supplies and how to reach it, and none of
   * what values it — so the tax, bank and terms section is not on his form and his save goes
   * to the field endpoint, which cannot reach those columns at all. The account is money and
   * stays behind the money permission, so his rows lead nowhere he may not go.</p>
   */
  it('lets the field name a supplier without the office half of the form', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ['masterdata:provisional:supplier'];
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    const row = within(screen.getByRole('table')).getByText('KSN-STEEL').closest('tr')!;
    expect(within(row).queryByRole('link', { name: 'Account' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    await user.type(await screen.findByLabelText('Firm’s name'), 'Kosi Sand Suppliers');
    expect(screen.queryByLabelText('GSTIN (optional)')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Credit days')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/vendors/field');
    expect(body.name).toBe('Kosi Sand Suppliers');
    // Not sent as blanks either: naming a thing and valuing it are two different acts.
    expect(body).not.toHaveProperty('creditDays');
    expect(body).not.toHaveProperty('active');
  });

  /** The same fields a day later, through the twin endpoint. Nothing carrying a number. */
  it('lets the field correct what it said about a supplier', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ['masterdata:provisional:supplier'];
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    const row = within(screen.getByRole('table')).getByText('KSN-STEEL').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    const contact = await screen.findByLabelText('Contact person (optional)');
    await user.clear(contact);
    await user.type(contact, 'Naveen Bisht');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/vendors/v1/field');
    expect(body.contactPerson).toBe('Naveen Bisht');
    expect(body).not.toHaveProperty('gstin');
  });

  /**
   * "Other" is a real answer and an empty one. The five kinds will never fit the scaffolding
   * hire, the surveyor and the man with the water tanker, and a register row reading "Other"
   * tells the next reader only what he already knew.
   */
  it('asks what he supplies when the list has no word for him', async () => {
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    await user.type(await screen.findByLabelText('Firm’s name'), 'Kausani Scaffolding Works');
    // Not asked for until it is the answer: a note behind a kind that no longer shows it is
    // a second answer nobody can see.
    expect(screen.queryByLabelText('What does he supply?')).not.toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: 'What he supplies' }));
    await user.click(await screen.findByRole('option', { name: 'Something else' }));

    const box = await screen.findByLabelText('What does he supply?');
    // And required with it, or the answer is the one it replaced.
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));
    expect(await screen.findByText('Say what he supplies')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    await user.type(box, 'Scaffolding on hire');
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { suppliesNote?: string }];
    expect(url).toBe('/vendors');
    expect(body.suppliesNote).toBe('Scaffolding on hire');
  });

  /** The register calls him what he was called, not "Other". */
  it('lists his own words where the list had none for him', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/vendors') {
        return Promise.resolve({
          data: page([
            {
              ...VENDORS[0]!,
              vendorType: 'OTHER' as const,
              suppliesNote: 'Scaffolding on hire',
            },
          ]),
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderRegister();

    await screen.findAllByText('Kausani Steel Traders');
    const row = within(screen.getByRole('table')).getByText('KSN-STEEL').closest('tr')!;
    expect(within(row).getByText('Scaffolding on hire')).toBeInTheDocument();
    expect(within(row).queryByText('Something else')).not.toBeInTheDocument();
  });

  it('refuses a half-typed GSTIN before the round trip', async () => {
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    await user.type(await screen.findByLabelText('Firm’s name'), 'Kosi Sand Suppliers');
    await user.type(screen.getByLabelText('GSTIN (optional)'), '05AABCU');
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));

    expect(await screen.findByText(/A GSTIN is 15 characters/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it('keeps the account out of the hands of somebody who may not see money', async () => {
    permissions = ['vendor:write'];
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    expect(screen.queryByRole('link', { name: 'Account' })).not.toBeInTheDocument();
  });
});

describe('VendorDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['vendor:write', 'vendor:balance:manage', 'payment:record'];
    get.mockImplementation((url: string) => {
      if (url === '/vendors/v1/account') return Promise.resolve({ data: ACCOUNT });
      if (url === '/vendors/v1/purchases') return Promise.resolve({ data: PURCHASES });
      if (url === '/payments') return Promise.resolve({ data: page(PAYMENTS) });
      if (url === '/vendors/v1/labour') return Promise.resolve({ data: LABOUR });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
  });

  /**
   * The other half of "how do I use a supplier on a site". Nothing is set up: he becomes
   * used the moment a supervisor names him on a day's labour, and this reads it backwards.
   */
  it('shows where the supplier’s men have worked, and whether he stood there', async () => {
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    // Two days at the same site, so the date is what tells the rows apart.
    const row = (await screen.findByText('2025-06-12')).closest('tr')!;
    expect(within(row).getByText('KSN-A')).toBeInTheDocument();
    expect(within(row).getByText('11')).toBeInTheDocument();
    expect(within(row).getByText('88')).toBeInTheDocument();
    expect(within(row).getByText('Karam Singh')).toBeInTheDocument();

    // The day he did not come says so, rather than leaving the cell blank.
    const absent = screen.getByText('2025-06-11').closest('tr')!;
    expect(within(absent).getByText('No')).toBeInTheDocument();
    expect(within(absent).getByText('—')).toBeInTheDocument();   // nobody noted hours
  });

  /**
   * The figures stay apart, and the net is a sentence. A signed number is read as an amount
   * owed by whoever is looking at it about half the time.
   */
  it('shows the account as terms rather than as one balance', async () => {
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    expect(screen.getByText('Advance paid')).toBeInTheDocument();
    expect(screen.getByText('Billed')).toBeInTheDocument();
    expect(screen.getByText(/We are ahead of him by/)).toBeInTheDocument();
  });

  it('names an unpriced delivery as unpriced rather than as free', async () => {
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    const row = screen.getByText('Steel TMT Fe-500D').closest('tr')!;
    expect(within(row).getByText('not priced')).toBeInTheDocument();
    expect(within(row).getByText('Not checked in')).toBeInTheDocument();
  });

  it('marks a payment with no bill behind it as an advance', async () => {
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    const row = screen.getByText('PAY-2025-0004').closest('tr')!;
    expect(within(row).getByText('Advance, no bill')).toBeInTheDocument();
  });

  it('records an advance against the supplier and against no bill', async () => {
    const user = userEvent.setup({ delay: null });
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Pay an advance' }));
    await user.clear(await screen.findByLabelText('Amount'));
    await user.type(screen.getByLabelText('Amount'), '15000');
    await user.type(screen.getByLabelText('What it is against'), 'Against the steel order');
    await user.click(screen.getByRole('button', { name: 'Record the advance' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/vendors/v1/advances');
    expect(post.mock.calls[0]![1]).toMatchObject({
      amount: 15000,
      remarks: 'Against the steel order',
    });
  });

  /** An advance nobody explained is unanswerable six months later, so the box is required. */
  it('will not record an advance with no reason on it', async () => {
    const user = userEvent.setup({ delay: null });
    renderAccount();
    await screen.findByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Pay an advance' }));
    await user.clear(await screen.findByLabelText('Amount'));
    await user.type(screen.getByLabelText('Amount'), '15000');
    await user.click(screen.getByRole('button', { name: 'Record the advance' }));

    expect(await screen.findByText(/Say what it is against/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });
});

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { VendorDetailPage } from './VendorDetailPage';
import { VendorsPage } from './VendorsPage';
import type { PageResponse, Vendor, VendorAccount, VendorPayment, VendorPurchase } from './types';

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
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
  });

  it('lists a supplier with the details somebody has to be able to reach', async () => {
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    const row = within(screen.getByRole('table')).getByText('KSN-STEEL').closest('tr')!;
    expect(within(row).getByText('Harish Bisht · 9812345678')).toBeInTheDocument();
    expect(within(row).getByText('05AABCU9603R1ZM')).toBeInTheDocument();
    expect(within(row).getByText('30 days')).toBeInTheDocument();
  });

  /** A code, a name and a kind. Everything else waits for the paperwork to catch up. */
  it('onboards a supplier from a code and a name alone', async () => {
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    await user.type(await screen.findByLabelText('Short code'), 'KSN-SAND');
    await user.type(screen.getByLabelText('Firm’s name'), 'Kosi Sand Suppliers');
    await user.click(screen.getByRole('button', { name: 'Onboard supplier' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { code: string; gstin?: string }];
    expect(url).toBe('/vendors');
    expect(body.code).toBe('KSN-SAND');
    // An empty box is "not given", not a blank GSTIN stored as a GSTIN.
    expect(body.gstin).toBeUndefined();
  });

  it('refuses a half-typed GSTIN before the round trip', async () => {
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await screen.findAllByText('Kausani Steel Traders');

    await user.click(screen.getByRole('button', { name: 'Onboard a supplier' }));
    await user.type(await screen.findByLabelText('Short code'), 'KSN-SAND');
    await user.type(screen.getByLabelText('Firm’s name'), 'Kosi Sand Suppliers');
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
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
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

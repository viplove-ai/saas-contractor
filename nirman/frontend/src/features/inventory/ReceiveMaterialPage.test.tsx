import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReceiveMaterialPage } from './ReceiveMaterialPage';
import type { Material, PageResponse, Receipt, Site, Store, Unit } from './types';

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

let permissions = ['inventory:receive', 'inventory:read'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-sup', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const STORES: Store[] = [
  {
    id: 'store-a',
    siteId: 'site-a',
    code: 'KSN-A-ST1',
    name: 'Main Block Store',
    defaultStore: true,
    active: true,
  },
];

const UNITS: Unit[] = [
  { id: 'unit-bag', code: 'BAG', name: 'Bag', decimalPlaces: 0 },
  { id: 'unit-kg', code: 'KG', name: 'Kilogram', decimalPlaces: 3 },
  { id: 'unit-mt', code: 'MT', name: 'Metric Tonne', decimalPlaces: 3 },
];

const MATERIALS: Material[] = [
  {
    id: 'mat-cement',
    code: 'CEM-OPC43',
    name: 'Cement OPC 43 Grade',
    baseUnitId: 'unit-bag',
    minStockLevel: 50,
    gstPercent: 28,
    active: true,
  },
  {
    id: 'mat-steel',
    code: 'STL-FE500D',
    name: 'Steel TMT Fe-500D',
    baseUnitId: 'unit-kg',
    minStockLevel: 500,
    gstPercent: 18,
    active: true,
  },
];

const NO_RECEIPTS: PageResponse<Receipt> = {
  content: [],
  page: 0,
  size: 50,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

const WAITING: PageResponse<Receipt> = {
  ...NO_RECEIPTS,
  totalElements: 1,
  content: [
    {
      id: 'g9',
      grnNumber: 'GRN-2025-0009',
      siteId: 'site-a',
      storeId: 'store-a',
      receiptDate: '2025-06-02',
      challanNumber: 'SS/856',
      subTotal: 41500,
      gstAmount: 11620,
      totalAmount: 53120,
      workflowStatus: 'SUBMITTED',
      version: 0,
      lines: [
        {
          id: 'l1',
          materialId: 'mat-cement',
          materialCode: 'CEM-OPC43',
          materialName: 'Cement OPC 43 Grade',
          unitCode: 'BAG',
          quantity: 100,
          quantityBase: 100,
          baseUnitCode: 'BAG',
          rate: 415,
          rateBase: 415,
          gstPercent: 28,
          gstAmount: 11620,
          amount: 41500,
        },
      ],
    },
  ],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReceiveMaterialPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(receipts: PageResponse<Receipt> = NO_RECEIPTS) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/sites/site-a/stores') return Promise.resolve({ data: STORES });
    if (url === '/units') return Promise.resolve({ data: UNITS });
    if (url === '/materials')
      return Promise.resolve({ data: { ...NO_RECEIPTS, content: MATERIALS } });
    if (url === '/inventory/goods-receipts') return Promise.resolve({ data: receipts });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('ReceiveMaterialPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['inventory:receive', 'inventory:read'];
    mockGets();
    post.mockResolvedValue({ data: { id: 'g1', grnNumber: 'GRN-2025-0001', lines: [] } });
  });

  /**
   * The unit the material is stocked in is the right default, and it stays a picker for the
   * delivery that arrives in tonnes. Making the storekeeper convert at the gate is asking
   * him to make an arithmetic mistake on a lorry driver's schedule.
   */
  it('preselects the material’s own unit but leaves it changeable', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Steel TMT Fe-500D' }));

    expect(screen.getByRole('combobox', { name: 'Unit' })).toHaveTextContent('KG');

    await user.click(screen.getByRole('combobox', { name: 'Unit' }));
    await user.click(await screen.findByRole('option', { name: 'MT' }));
    expect(screen.getByRole('combobox', { name: 'Unit' })).toHaveTextContent('MT');
  });

  it('sends a client-generated id, so a delivery synced twice is one delivery', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '100');
    await user.type(screen.getByRole('spinbutton', { name: 'Rate' }), '415');
    await user.type(screen.getByRole('textbox', { name: 'Challan number' }), 'SS/856');
    await user.click(screen.getByRole('button', { name: /Book 1 material/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { id: string; challanNumber: string; lines: unknown[] },
    ];
    expect(url).toBe('/inventory/goods-receipts');
    expect(body.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(body.challanNumber).toBe('SS/856');
    expect(body.lines).toEqual([
      { materialId: 'mat-cement', unitId: 'unit-bag', quantity: 100, rate: 415 },
    ]);
  });

  it('will not book a line that is missing a rate', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '100');

    expect(screen.getByRole('button', { name: /Book 0 material/ })).toBeDisabled();
  });

  /** Booking a delivery is not the same act as agreeing that it arrived. */
  it('says plainly that a booked delivery has not reached the store yet', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '10');
    await user.type(screen.getByRole('spinbutton', { name: 'Rate' }), '415');
    await user.click(screen.getByRole('button', { name: /Book 1 material/ }));

    expect(
      await screen.findByText(/reaches the store once an engineer checks it/),
    ).toBeInTheDocument();
  });

  it('shows a supervisor the waiting queue without offering him the sign-off', async () => {
    mockGets(WAITING);
    renderPage();

    expect(await screen.findByText('GRN-2025-0009')).toBeInTheDocument();
    expect(screen.getByText(/challan SS\/856/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Matches challan' })).not.toBeInTheDocument();
  });

  it('lets an engineer check a delivery against the challan', async () => {
    permissions = ['inventory:receive', 'inventory:read', 'inventory:verify'];
    mockGets(WAITING);
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('GRN-2025-0009');

    await user.click(screen.getByRole('button', { name: 'Matches challan' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/inventory/goods-receipts/g9/verify');
    expect(post.mock.calls[0]![1]).toMatchObject({ action: 'VERIFY' });
  });
});

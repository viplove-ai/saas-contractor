import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReceiveMaterialPage } from './ReceiveMaterialPage';
import type {
  Material,
  PageResponse,
  Receipt,
  ReceiptLineResponse,
  Site,
  Store,
  Unit,
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
    provisional: false,
    version: 0,
  },
  {
    id: 'mat-steel',
    code: 'STL-FE500D',
    name: 'Steel TMT Fe-500D',
    baseUnitId: 'unit-kg',
    altUnitIds: ['unit-mt'],
    minStockLevel: 500,
    gstPercent: 18,
    active: true,
    provisional: false,
    version: 0,
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

function stripRate(line: ReceiptLineResponse): ReceiptLineResponse {
  const { rate: _rate, rateBase: _rateBase, ...rest } = line;
  return { ...rest, gstAmount: 0, amount: 0 };
}

/** The same delivery as it actually arrives: booked at the gate, with no rate on it yet. */
const WAITING_UNPRICED: PageResponse<Receipt> = {
  ...WAITING,
  content: [
    {
      ...WAITING.content[0]!,
      subTotal: 0,
      gstAmount: 0,
      totalAmount: 0,
      // The keys are dropped rather than set to undefined: the API omits nulls, so an
      // unpriced line arrives with no rate field at all.
      lines: [stripRate(WAITING.content[0]!.lines[0]!)],
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
    put.mockResolvedValue({ data: {} });
    post.mockResolvedValue({ data: { id: 'g1', grnNumber: 'GRN-2025-0001', lines: [] } });
  });

  /**
   * The unit the material is stocked in is the right default, and it stays a picker for the
   * delivery that arrives in tonnes. Making the storekeeper convert at the gate is asking
   * him to make an arithmetic mistake on a lorry driver's schedule.
   */
  /**
   * Offering every unit was offering units the server refuses — and it refuses them after
   * the whole delivery has been typed. Cement is stocked in bags and converts to nothing.
   */
  it('offers only the units a material can actually be booked in', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.click(screen.getByRole('combobox', { name: 'Unit' }));
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['BAG']);
  });

  /** Steel is stocked in kilograms and arrives by the tonne, so both are on offer. */
  it('offers the base unit and whatever the material converts from', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Steel TMT Fe-500D' }));
    await user.click(screen.getByRole('combobox', { name: 'Unit' }));
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['KG', 'MT']);
  });

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

  /**
   * A lorry turns up with something the catalogue has never heard of. The alternative to
   * letting him name it is a delivery nobody ever books, so the picker has a last answer —
   * and what he types becomes a real material, because a receipt line carries an id and a
   * name has nowhere else to live.
   */
  it('lets the storekeeper name a material the catalogue does not have', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ['inventory:receive', 'inventory:read', 'masterdata:provisional'];
    post.mockImplementation((url: string) =>
      url === '/materials/field'
        ? Promise.resolve({
            data: { id: 'mat-new', code: 'MAT-2026-0001', name: 'Tile Adhesive', provisional: true },
          })
        : Promise.resolve({ data: { id: 'g1', grnNumber: 'GRN-2025-0002', lines: [] } }),
    );
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Not in the list…' }));
    await user.type(screen.getByRole('textbox', { name: 'What is it called' }), 'Tile Adhesive');
    // No catalogue row means no base unit to fall back on, so he says what it is counted in.
    await user.click(screen.getByRole('combobox', { name: 'Unit' }));
    await user.click(await screen.findByRole('option', { name: 'BAG' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '12');
    await user.click(screen.getByRole('button', { name: /Book 1 material/ }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    // Named first, because the receipt cannot be posted until the line has an id to carry.
    expect(post.mock.calls[0]![0]).toBe('/materials/field');
    expect(post.mock.calls[0]![1]).toEqual({ name: 'Tile Adhesive', baseUnitId: 'unit-bag' });
    expect(post.mock.calls[1]![0]).toBe('/inventory/goods-receipts');
    expect((post.mock.calls[1]![1] as { lines: unknown[] }).lines).toEqual([
      { materialId: 'mat-new', unitId: 'unit-bag', quantity: 12 },
    ]);
  });

  /** Naming a material is its own permission. Without it the answer is not on offer. */
  it('does not offer to name one to somebody who may not', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));

    expect(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Not in the list…' })).not.toBeInTheDocument();
  });

  it('sends a client-generated id, so a delivery synced twice is one delivery', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '100');
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
    // No rate: the storekeeper does not price a delivery, and the server refuses one
    // from him outright rather than dropping it.
    expect(body.lines).toEqual([
      { materialId: 'mat-cement', unitId: 'unit-bag', quantity: 100 },
    ]);
  });

  /**
   * The rate is not merely optional for him, it is absent. A disabled box would say the
   * number is his and he is not allowed to change it; there is no box, because the answer
   * is not his to give.
   */
  it('never asks the storekeeper for a rate', async () => {
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    expect(screen.queryByRole('spinbutton', { name: 'Rate' })).not.toBeInTheDocument();
    expect(screen.getByText('The office prices this against the invoice.')).toBeInTheDocument();
  });

  /** What arrived and how much of it is the whole of his answer, and it is enough. */
  it('books a line the storekeeper has no rate for', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: 'Cement OPC 43 Grade' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '100');

    expect(screen.getByRole('button', { name: /Book 1 material/ })).toBeEnabled();
  });

  /** Somebody who may price one is still held to filling the box that is in front of him. */
  it('will not book a line whose rate the office left blank', async () => {
    permissions = ['inventory:receive', 'inventory:read', 'inventory:price'];
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
    await user.click(screen.getByRole('button', { name: /Book 1 material/ }));

    expect(
      await screen.findByText(/reaches the store once the office prices it/),
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
    expect(put).not.toHaveBeenCalled();
  });

  /**
   * The two calls in order, on one press. Pricing and checking are separate acts on the
   * server; at a small site they are one person with the invoice in front of him.
   */
  it('prices an unpriced delivery on the way to verifying it', async () => {
    permissions = ['inventory:receive', 'inventory:read', 'inventory:verify', 'inventory:price'];
    mockGets(WAITING_UNPRICED);
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('GRN-2025-0009');

    // Nothing can be signed off until somebody has said what it cost.
    expect(screen.getByRole('button', { name: 'Matches challan' })).toBeDisabled();

    await user.type(
      screen.getByRole('spinbutton', { name: 'Cement OPC 43 Grade' }),
      '415',
    );
    await user.click(screen.getByRole('button', { name: 'Matches challan' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(put.mock.calls[0]![0]).toBe('/inventory/goods-receipts/g9/prices');
    expect(put.mock.calls[0]![1]).toEqual({ lines: [{ lineId: 'l1', rate: 415 }] });
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/inventory/goods-receipts/g9/verify');
  });

  /** He can see it is stuck, and why, without being handed a box that is not his. */
  it('tells the storekeeper an unpriced delivery is waiting on the office', async () => {
    mockGets(WAITING_UNPRICED);
    renderPage();
    await screen.findByText('GRN-2025-0009');

    expect(screen.getByText(/Cement OPC 43 Grade — 100 BAG · not priced yet/)).toBeInTheDocument();
    expect(screen.getByText(/Waiting on the office to price it/)).toBeInTheDocument();
    expect(screen.queryByRole('spinbutton', { name: 'Cement OPC 43 Grade' })).not.toBeInTheDocument();
  });
});

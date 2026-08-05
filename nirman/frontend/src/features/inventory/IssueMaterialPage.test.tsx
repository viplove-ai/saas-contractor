import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IssueMaterialPage } from './IssueMaterialPage';
import type { BoqItem, Issue, PageResponse, Site, StockPosition, Store, Unit } from './types';

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

/** A supervisor: he may issue material, but signing it off is somebody else's job. */
let permissions = ['inventory:issue', 'inventory:read'];

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
];

const BOQ_ITEMS: BoqItem[] = [
  {
    id: 'boq-1',
    projectId: 'p1',
    siteId: 'site-a',
    itemNumber: 'B-001',
    description: 'RCC M25 in columns',
    category: 'Concrete & RCC',
    synthetic: false,
  },
  // A parser placeholder. Offering it and then having the server refuse it would be a
  // worse answer than never offering it, so the list drops it.
  {
    id: 'boq-99',
    projectId: 'p1',
    itemNumber: 'B-UNALLOC',
    description: 'UNALLOCATED — reconciliation placeholder',
    synthetic: true,
  },
];

/** Cement is in stock; the steel row is empty and must not be offered at all. */
const STOCK: StockPosition = {
  totalValue: 49800,
  rows: [
    {
      storeId: 'store-a',
      storeName: 'Main Block Store',
      materialId: 'mat-cement',
      materialCode: 'CEM-OPC43',
      materialName: 'Cement OPC 43 Grade',
      baseUnitCode: 'BAG',
      quantityBase: 120,
      movingAvgRate: 415,
      stockValue: 49800,
      inTransitQtyBase: 0,
      minStockLevel: 50,
      low: false,
    },
    {
      storeId: 'store-a',
      storeName: 'Main Block Store',
      materialId: 'mat-steel',
      materialCode: 'STL-FE500D',
      materialName: 'Steel TMT Fe-500D',
      baseUnitCode: 'KG',
      quantityBase: 0,
      movingAvgRate: 62,
      stockValue: 0,
      inTransitQtyBase: 0,
      minStockLevel: 500,
      low: true,
    },
  ],
};

const NO_ISSUES: PageResponse<Issue> = {
  content: [],
  page: 0,
  size: 50,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <IssueMaterialPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('IssueMaterialPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['inventory:issue', 'inventory:read'];
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/sites/site-a/stores') return Promise.resolve({ data: STORES });
      if (url === '/units') return Promise.resolve({ data: UNITS });
      if (url === '/boq-items') return Promise.resolve({ data: BOQ_ITEMS });
      if (url === '/inventory/stock') return Promise.resolve({ data: STOCK });
      if (url === '/inventory/issues') return Promise.resolve({ data: NO_ISSUES });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({
      data: { id: 'i1', issueNumber: 'ISS-2025-0001', lines: [] },
    });
  });

  it('offers only what the store actually holds, with the quantity alongside', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    const options = await screen.findAllByRole('option');

    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('Cement OPC 43 Grade');
    expect(options[0]).toHaveTextContent('120 BAG in stock');
    // Steel is at zero. There is nothing to issue, so it is not a choice.
    expect(screen.queryByText(/Steel TMT/)).not.toBeInTheDocument();
  });

  it('leaves the parser placeholder out of the work items an issue can be charged to', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'For which work' });

    await user.click(screen.getByRole('combobox', { name: 'For which work' }));

    /*
      Waiting for a real work item before asserting the placeholder is absent, and that order
      is the whole point. The list opens the instant it is clicked, carrying only its own
      "Not against a work item" row; the BOQ items arrive when /boq-items answers. Asserting
      the parser placeholder is missing from a list that has not loaded yet passes for
      entirely the wrong reason — which is what made this test fail about one run in twenty,
      on the runs where the query happened to land after the click rather than before it.
    */
    expect(await screen.findByRole('option', { name: /B-001/ })).toBeInTheDocument();

    const listbox = screen.getByRole('listbox');
    expect(within(listbox).queryByText(/B-UNALLOC/)).not.toBeInTheDocument();
  });

  it('will not send a slip with nowhere to charge the material', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: /Cement/ }));
    await user.type(screen.getByRole('spinbutton', { name: /Quantity/ }), '20');

    // A complete line, and still nothing to charge it to: no work item and no purpose.
    expect(screen.getByRole('button', { name: /Issue 1 material/ })).toBeDisabled();

    await user.type(screen.getByRole('textbox', { name: 'What is it for' }), 'Column shuttering');
    expect(screen.getByRole('button', { name: /Issue 1 material/ })).toBeEnabled();
  });

  it('refuses a quantity the store cannot cover, as it is typed', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: /Cement/ }));
    await user.type(screen.getByRole('spinbutton', { name: /Quantity/ }), '200');

    expect(await screen.findByText('Only 120 BAG in this store')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Issue 0 material/ })).toBeDisabled();
  });

  it('sends a client-generated id and the base unit, so a re-send cannot duplicate the slip', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Material' });

    await user.click(screen.getByRole('combobox', { name: 'For which work' }));
    await user.click(await screen.findByRole('option', { name: /B-001/ }));
    await user.click(screen.getByRole('combobox', { name: 'Material' }));
    await user.click(await screen.findByRole('option', { name: /Cement/ }));
    await user.type(screen.getByRole('spinbutton', { name: /Quantity/ }), '20');
    await user.click(screen.getByRole('button', { name: /Issue 1 material/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { id: string; boqItemId: string; lines: { unitId: string; quantity: number }[] },
    ];
    expect(url).toBe('/inventory/issues');
    expect(body.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(body.boqItemId).toBe('boq-1');
    expect(body.lines).toEqual([{ materialId: 'mat-cement', unitId: 'unit-bag', quantity: 20 }]);
  });

  it('does not offer approval to somebody who cannot approve', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/sites/site-a/stores') return Promise.resolve({ data: STORES });
      if (url === '/units') return Promise.resolve({ data: UNITS });
      if (url === '/boq-items') return Promise.resolve({ data: BOQ_ITEMS });
      if (url === '/inventory/stock') return Promise.resolve({ data: STOCK });
      if (url === '/inventory/issues')
        return Promise.resolve({
          data: {
            ...NO_ISSUES,
            totalElements: 1,
            content: [
              {
                id: 'i9',
                issueNumber: 'ISS-2025-0009',
                siteId: 'site-a',
                storeId: 'store-a',
                issueDate: '2025-06-02',
                purpose: 'Column shuttering',
                workflowStatus: 'SUBMITTED',
                totalValue: 0,
                version: 0,
                lines: [],
              },
            ],
          },
        });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });

    renderPage();
    expect(await screen.findByText('ISS-2025-0009')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('offers approval to an engineer', async () => {
    permissions = ['inventory:issue', 'inventory:read', 'inventory:verify'];
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/sites/site-a/stores') return Promise.resolve({ data: STORES });
      if (url === '/units') return Promise.resolve({ data: UNITS });
      if (url === '/boq-items') return Promise.resolve({ data: BOQ_ITEMS });
      if (url === '/inventory/stock') return Promise.resolve({ data: STOCK });
      if (url === '/inventory/issues')
        return Promise.resolve({
          data: {
            ...NO_ISSUES,
            totalElements: 1,
            content: [
              {
                id: 'i9',
                issueNumber: 'ISS-2025-0009',
                siteId: 'site-a',
                storeId: 'store-a',
                issueDate: '2025-06-02',
                workflowStatus: 'SUBMITTED',
                totalValue: 0,
                version: 0,
                lines: [],
              },
            ],
          },
        });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });

    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('ISS-2025-0009');

    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/inventory/issues/i9/approve');
  });
});

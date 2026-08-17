import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StockCorrectionsPanel } from './StockCorrectionsPanel';
import type { StockCorrection } from './types';

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

let permissions: string[] = [];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-1', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const WAITING: StockCorrection = {
  id: 'sc-1',
  siteId: 'site-a',
  storeId: 'store-a',
  storeName: 'site-KSN-A',
  materialId: 'mat-cement',
  materialName: 'Cement OPC 43 Grade',
  unitId: 'unit-bag',
  unitCode: 'BAG',
  quantityDelta: -1,
  correctionDate: '2026-08-12',
  reason: 'Twelve on the challan, eleven off the lorry',
  status: 'PENDING',
  createdAt: '2026-08-12T04:00:00Z',
  createdBy: 'u-1',
  version: 0,
};

function renderPanel(rows: StockCorrection[] = [WAITING]) {
  get.mockImplementation((url: string) => {
    if (url === '/inventory/stock-corrections') return Promise.resolve({ data: rows });
    if (url === '/materials') return Promise.resolve({ data: { content: [] } });
    if (url === '/units') return Promise.resolve({ data: [] });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StockCorrectionsPanel storeId="store-a" siteId="site-a" />
    </QueryClientProvider>,
  );
}

/**
 * RecordTable draws the table and the phone cards at once and lets a breakpoint choose, so
 * every row's text is in the DOM twice. These assertions go through the table.
 */
const table = () => screen.getByRole('table', { name: 'Stock corrections' });
const findQueue = async () => {
  await screen.findAllByText('Cement OPC 43 Grade');
  return within(table());
};

describe('StockCorrectionsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['inventory:read', 'inventory:correct'];
  });

  /**
   * The storekeeper's half of the split, and the sentence the screen exists for: he can say
   * the figure is wrong and cannot change it. Showing him a Post it button that the server
   * would refuse is worse than not showing it — he learns the screen lies.
   */
  it('lets the store report a difference and offers it no way to post one', async () => {
    renderPanel();
    const queue = await findQueue();

    expect(screen.getByRole('button', { name: 'Report a difference' })).toBeInTheDocument();
    expect(queue.getByText('Waiting on the office')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Post it' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Not so' })).not.toBeInTheDocument();
  });

  /** Short or over, in the words the man who counted the shed would use. */
  it('says which way the figure is out, in the unit it was counted in', async () => {
    const queue = await (renderPanel(), findQueue());

    expect(queue.getByText(/1 BAG short/)).toBeInTheDocument();
  });

  /**
   * The office's half. Accepting is a posting — it produces the ordinary signed adjustment —
   * so it is held by the same permission that lets an administrator type one himself.
   */
  it('offers the office the decision, and sends it', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ['inventory:read', 'inventory:adjust'];
    post.mockResolvedValue({ data: { ...WAITING, status: 'ACCEPTED', postedTxnId: 'txn-9' } });
    renderPanel();
    const queue = await findQueue();

    // He may decide and, on this screen, is not the one raising them.
    expect(screen.queryByRole('button', { name: 'Report a difference' })).not.toBeInTheDocument();
    await user.click(queue.getByRole('button', { name: 'Post it' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/inventory/stock-corrections/sc-1/decision');
    expect((post.mock.calls[0]![1] as { action: string }).action).toBe('ACCEPT');
  });

  /**
   * A refused request keeps its place and its reason. One that vanished when it was turned
   * down would be a storekeeper who counts the shed once and never types it again.
   */
  it('keeps a refused request, with the answer it was given', async () => {
    renderPanel([
      {
        ...WAITING,
        status: 'REJECTED',
        decisionRemarks: 'They went to the other store on Tuesday',
      },
    ]);
    const queue = await findQueue();

    expect(queue.getByText('Office says no')).toBeInTheDocument();
    expect(queue.getByText('They went to the other store on Tuesday')).toBeInTheDocument();
  });
});

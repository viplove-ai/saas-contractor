import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createQueryClient } from '../../app/queryClient';
import { offlineDb } from '../../offline/db';
import { AddExpensePage } from './AddExpensePage';
import type { Expense, ExpenseCategory, PageResponse, Site } from './types';

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

let permissions = ['expense:create', 'expense:read'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-sup', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const CATEGORIES: ExpenseCategory[] = [
  {
    id: 'cat-misc',
    code: 'MISC',
    name: 'Miscellaneous',
    materialPurchase: false,
    labourPayment: false,
    requiresVendor: false,
    active: true,
  },
  {
    id: 'cat-site',
    code: 'MISC-SITE',
    name: 'Site Expenses',
    parentId: 'cat-misc',
    materialPurchase: false,
    labourPayment: false,
    requiresVendor: false,
    active: true,
  },
];

const NO_EXPENSES: PageResponse<Expense> = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

/**
 * The `client` seam exists for the offline case at the bottom of this file: that one has to
 * run against the app's own query defaults, because what it is checking is one of them.
 */
function renderPage(client?: QueryClient) {
  const queryClient =
    client ?? new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AddExpensePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(expenses: PageResponse<Expense> = NO_EXPENSES) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/expense-categories') return Promise.resolve({ data: CATEGORIES });
    if (url === '/expenses') return Promise.resolve({ data: expenses });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

/** The 409 shape the server actually returns, candidates and all. */
function duplicateError() {
  const error = new Error('Request failed with status code 409') as Error & {
    isAxiosError: boolean;
    response: unknown;
  };
  error.isAxiosError = true;
  error.response = {
    status: 409,
    data: {
      detail: 'This looks like an expense already on file.',
      candidates: [
        {
          id: 'e-old',
          expenseNumber: 'EXP-2025-0007',
          expenseDate: '2025-06-01',
          description: 'Cartage from Haldwani',
          billNumber: 'SS/856',
          totalAmount: 8000,
          workflowStatus: 'APPROVED',
          matchedOn: 'same vendor and bill number',
        },
      ],
    },
  };
  return error;
}

describe('AddExpensePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['expense:create', 'expense:read'];
    mockGets();
    post.mockResolvedValue({ data: { id: 'e1', expenseNumber: 'EXP-2025-0001' } });
  });

  /**
   * The bill states the amount and the rate separately, so the screen does too — and shows
   * the total it derives rather than asking anybody to type it.
   */
  it('derives the total from the amount and the tax rate', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '10000');
    await user.clear(screen.getByRole('spinbutton', { name: 'GST %' }));
    await user.type(screen.getByRole('spinbutton', { name: 'GST %' }), '18');

    expect(await screen.findByText(/Total with tax: ₹11,800/)).toBeInTheDocument();
  });

  it('sends a client-generated id so an expense synced twice is one expense', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
    await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Cartage');
    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '4000');
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { id: string; categoryId: string; amountBeforeTax: number },
    ];
    expect(url).toBe('/expenses');
    expect(body.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(body.categoryId).toBe('cat-site');
    expect(body.amountBeforeTax).toBe(4000);
  });

  /** Only the subcategories are offered: "Miscellaneous" is a heading, not a thing to book to. */
  it('offers the subcategories rather than the top-level headings', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'What kind' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    const options = await screen.findAllByRole('option');

    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('Site Expenses');
  });

  /**
   * The heart of the duplicate flow: show what it collided with, and make the override
   * explain itself. Refusing without the candidate is what sends somebody hunting.
   */
  it('shows the candidates behind a duplicate warning and demands a reason to override', async () => {
    post.mockRejectedValueOnce(duplicateError());
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
    await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Cartage');
    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '8000');
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    expect(await screen.findByText(/EXP-2025-0007/)).toBeInTheDocument();
    expect(screen.getByText(/same vendor and bill number/)).toBeInTheDocument();

    // No reason yet, so booking it anyway is not offered.
    const override = screen.getByRole('button', { name: 'Book it anyway' });
    expect(override).toBeDisabled();

    await user.type(
      screen.getByRole('textbox', { name: 'If it really is separate, say why' }),
      'Second lorry the same afternoon',
    );
    expect(override).toBeEnabled();

    post.mockResolvedValueOnce({ data: { id: 'e2', expenseNumber: 'EXP-2025-0008' } });
    await user.click(override);

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    const [, body, config] = post.mock.calls[1] as [
      string,
      { duplicateOverrideReason: string },
      { params: { force: boolean } },
    ];
    expect(config.params).toEqual({ force: true });
    expect(body.duplicateOverrideReason).toBe('Second lorry the same afternoon');
  });

  /** Booking and sending are separate acts, and the draft says so. */
  it('saves as a draft and offers to send it separately', async () => {
    mockGets({
      ...NO_EXPENSES,
      totalElements: 1,
      content: [
        {
          id: 'e9',
          expenseNumber: 'EXP-2025-0009',
          siteId: 'site-a',
          expenseDate: '2025-06-02',
          categoryId: 'cat-site',
          categoryName: 'Site Expenses',
          description: 'Cartage',
          amountBeforeTax: 4000,
          gstPercent: 0,
          gstAmount: 0,
          totalAmount: 4000,
          paymentStatus: 'UNPAID',
          paidAmount: 0,
          payableAmount: 4000,
          workflowStatus: 'DRAFT',
          version: 0,
          attachments: [],
        },
      ],
    });
    const user = userEvent.setup({ delay: null });
    renderPage();

    expect(await screen.findByText('EXP-2025-0009')).toBeInTheDocument();
    expect(screen.getByText('Draft')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Send for approval' }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/expenses/e9/submit');
  });

  /** An approved row is nobody's to send again. */
  it('does not offer to send an expense that is already approved', async () => {
    mockGets({
      ...NO_EXPENSES,
      totalElements: 1,
      content: [
        {
          id: 'e8',
          expenseNumber: 'EXP-2025-0008',
          siteId: 'site-a',
          expenseDate: '2025-06-02',
          categoryId: 'cat-site',
          description: 'Cartage',
          amountBeforeTax: 4000,
          gstPercent: 0,
          gstAmount: 0,
          totalAmount: 4000,
          paymentStatus: 'UNPAID',
          paidAmount: 0,
          payableAmount: 4000,
          workflowStatus: 'APPROVED',
          version: 1,
          attachments: [],
        },
      ],
    });
    renderPage();

    expect(await screen.findByText('EXP-2025-0008')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send for approval' })).not.toBeInTheDocument();
  });

  /**
   * The expense booked with no signal, which is the same defect as the muster: React Query
   * pauses a mutation while the browser reports no connection, so `saveOrQueue` never runs
   * and the expense reaches neither the server nor the device. The `offline` event is fired
   * after the form is on screen because React Query's online manager starts out believing
   * there is a connection and only listens once a provider is mounted — which is also the
   * only order a real phone can produce.
   */
  it('keeps the expense on the phone when the signal goes, rather than stalling', async () => {
    const onLine = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: false });
    post.mockRejectedValue(new Error('Network Error'));

    try {
      const user = userEvent.setup({ delay: null });
      renderPage(createQueryClient());
      await screen.findByRole('combobox', { name: 'Site' });

      window.dispatchEvent(new Event('offline'));

      await user.click(screen.getByRole('combobox', { name: 'What kind' }));
      await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
      await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Cartage');
      await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '3400');
      await user.click(screen.getByRole('button', { name: 'Save as draft' }));

      expect(await screen.findByText(/saved on this phone/)).toBeInTheDocument();
      expect(post).not.toHaveBeenCalled();
      const queued = await offlineDb.drafts.toArray();
      expect(queued).toHaveLength(1);
      expect(queued[0]).toMatchObject({ entityType: 'EXPENSE', status: 'PENDING' });
    } finally {
      // Both are process-wide: the flag is read by saveOrQueue and the event by React
      // Query's online manager, and a later test file inherits whatever is left here.
      await offlineDb.drafts.clear();
      if (onLine) Object.defineProperty(window.navigator, 'onLine', onLine);
      window.dispatchEvent(new Event('online'));
    }
  });
});

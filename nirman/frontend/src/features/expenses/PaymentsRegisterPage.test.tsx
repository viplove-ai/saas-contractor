import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PaymentsRegisterPage } from './PaymentsRegisterPage';
import type { AllocationSummary, Expense, PageResponse, Site } from './types';

const get = vi.fn();
const put = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      put: (...args: unknown[]) => put(...args),
    },
  };
});

/** The administrator reading the month. A supervisor's version of this screen is below. */
let permissions = ['expense:read', 'expense:allocate'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

function expense(overrides: Partial<Expense> & { id: string; expenseNumber: string }): Expense {
  return {
    siteId: 'site-a',
    expenseDate: '2025-06-02',
    categoryId: 'cat-site',
    categoryName: 'Site Expenses',
    description: 'Cartage from Haldwani',
    amountBeforeTax: 4000,
    gstPercent: 0,
    gstAmount: 0,
    totalAmount: 4000,
    paymentStatus: 'UNPAID',
    paidAmount: 0,
    payableAmount: 4000,
    costAllocation: 'SITE',
    siteCost: 4000,
    companyCost: 0,
    revision: 0,
    workflowStatus: 'APPROVED',
    version: 1,
    attachments: [],
    ...overrides,
  };
}

const CARTAGE = expense({ id: 'e1', expenseNumber: 'EXP-2025-0001' });
const RENT = expense({
  id: 'e2',
  expenseNumber: 'EXP-2025-0002',
  description: 'Office rent',
  categoryName: 'Office Expenses',
  costAllocation: 'COMPANY',
  siteCost: 0,
  companyCost: 4000,
  allocatedAt: '2025-06-03T04:00:00Z',
});

const SUMMARY: AllocationSummary = {
  totalBooked: 8000,
  siteCost: 4000,
  companyCost: 4000,
  paid: 0,
  payable: 8000,
  expenseCount: 2,
  awaitingApproval: 0,
  unallocatedCount: 1,
};

function rows(content: Expense[]): PageResponse<Expense> {
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

function mockGets() {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/expense-categories') return Promise.resolve({ data: [] });
    if (url === '/vendors') return Promise.resolve({ data: rows([] as never[]) });
    if (url === '/expenses/summary') return Promise.resolve({ data: SUMMARY });
    if (url === '/expenses') return Promise.resolve({ data: rows([CARTAGE, RENT]) });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <PaymentsRegisterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PaymentsRegisterPage', () => {
  beforeEach(() => {
    permissions = ['expense:read', 'expense:allocate'];
    get.mockReset();
    put.mockReset();
    put.mockResolvedValue({ data: RENT });
    mockGets();
    localStorage.clear();
  });

  /** The label is the point of the screen: a total alone leaves every row to be re-decided. */
  it('says whose cost each record is', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.getByText('Site expense')).toBeInTheDocument();
    expect(screen.getByText('Company expense')).toBeInTheDocument();
  });

  /**
   * The totals come from the server over every matching row. A sum of the page on screen is
   * not a total, and labelling it one is worse than showing none.
   */
  it('shows what the filter adds up to, split the way the screen is', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.getByText('Booked')).toBeInTheDocument();
    expect(screen.getByText("The company's")).toBeInTheDocument();
    expect(
      screen.getByText(/still carry their head's proposal/),
    ).toBeInTheDocument();
  });

  /** A filter that is not set must not be sent, or it narrows the register to nothing. */
  it('sends only the filters that are set', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    const call = get.mock.calls.find(([url]) => url === '/expenses');
    const params = (call![1] as { params: Record<string, unknown> }).params;
    expect(params).not.toHaveProperty('status');
    expect(params).not.toHaveProperty('allocation');
    expect(params.from).toBeTruthy();
  });

  /** Whose cost it is, re-decided by the person reading the month. */
  it('re-books a record as the company’s', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    await user.click(screen.getAllByRole('button', { name: 'Change whose cost' })[0]!);
    await user.click(screen.getByRole('combobox', { name: 'Whose cost is it' }));
    await user.click(await screen.findByRole('option', { name: "The company's" }));
    await user.type(screen.getByRole('textbox', { name: 'Why' }), 'the office car');
    await user.click(screen.getByRole('button', { name: /Book it as company expense/ }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(put.mock.calls[0]![0]).toBe('/expenses/e1/allocation');
    expect(put.mock.calls[0]![1]).toEqual({
      allocation: 'COMPANY',
      note: 'the office car',
    });
  });

  /** A split has to be a split. The server refuses the whole amount; so does the button. */
  it('will not send a split that is the whole bill', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    await user.click(screen.getAllByRole('button', { name: 'Change whose cost' })[0]!);
    await user.click(screen.getByRole('combobox', { name: 'Whose cost is it' }));
    await user.click(await screen.findByRole('option', { name: 'Part each' }));
    await user.type(screen.getByRole('spinbutton', { name: "The site's part" }), '4000');

    expect(screen.getByRole('button', { name: /Book it as/ })).toBeDisabled();
    expect(put).not.toHaveBeenCalled();
  });

  /**
   * Reading the register and correcting it are different permissions. Somebody who can only
   * read it gets the register and no buttons — not a screen of refusals.
   */
  it('offers no correction to somebody who only reads the register', async () => {
    permissions = ['expense:read'];
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.queryByRole('button', { name: 'Change whose cost' })).not.toBeInTheDocument();
  });
});

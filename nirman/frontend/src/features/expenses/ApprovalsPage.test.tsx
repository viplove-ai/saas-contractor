import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApprovalsPage } from './ApprovalsPage';
import type { Expense, ExpenseWorkflow, PageResponse, Site } from './types';

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

/** An engineer: approves, and never pays. The accountant is the other way round. */
let permissions = ['expense:read', 'expense:approve:l1'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-eng', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

function page(content: Expense[]): PageResponse<Expense> {
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
    workflowStatus: 'SUBMITTED',
    version: 1,
    attachments: [],
    ...overrides,
  };
}

/** Waiting on the engineer; past the engineer and waiting on the office; approved and owed. */
const SUBMITTED = expense({
  id: 'e1',
  expenseNumber: 'EXP-2025-0001',
  workflowStatus: 'SUBMITTED',
  pendingLevel: 1,
  pendingWithRole: 'ENGINEER',
  billNumber: 'SS/856',
  createdByName: 'Uttam Singh',
  attachments: [
    {
      id: 'ea-1',
      attachmentId: 'att-1',
      docType: 'BILL',
      fileName: 'bill-ss-856.jpg',
      contentType: 'image/jpeg',
      sizeBytes: 240_000,
    },
  ],
});

const L1 = expense({
  id: 'e2',
  expenseNumber: 'EXP-2025-0002',
  totalAmount: 60000,
  amountBeforeTax: 60000,
  payableAmount: 60000,
  workflowStatus: 'L1_APPROVED',
  pendingLevel: 2,
  pendingWithRole: 'ADMIN',
});

const OWED = expense({
  id: 'e3',
  expenseNumber: 'EXP-2025-0003',
  workflowStatus: 'APPROVED',
  totalAmount: 50000,
  amountBeforeTax: 50000,
  paidAmount: 20000,
  payableAmount: 30000,
  paymentStatus: 'PARTIAL',
  vendorName: 'Shri Ji Traders',
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ApprovalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets() {
  get.mockImplementation((url: string, config?: { params?: { status?: ExpenseWorkflow } }) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/approvals/pending') return Promise.resolve({ data: [{ id: 'a1' }] });
    if (url === '/attachments/att-1/url') {
      return Promise.resolve({
        data: { url: 'https://minio.local/signed/att-1', fileName: 'bill-ss-856.jpg' },
      });
    }
    if (url === '/expenses') {
      const status = config?.params?.status;
      if (status === 'SUBMITTED') return Promise.resolve({ data: page([SUBMITTED]) });
      if (status === 'L1_APPROVED') return Promise.resolve({ data: page([L1]) });
      if (status === 'APPROVED') return Promise.resolve({ data: page([OWED]) });
      return Promise.resolve({ data: page([]) });
    }
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('ApprovalsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['expense:read', 'expense:approve:l1'];
    mockGets();
    post.mockResolvedValue({ data: { id: 'e1' } });
  });

  /**
   * A bill past the engineer and waiting on the office looks identical to one nobody has
   * touched unless the level is on the row.
   */
  it('shows which level each expense is waiting at, and on whom', async () => {
    renderPage();

    expect(await screen.findByText('EXP-2025-0001')).toBeInTheDocument();
    expect(screen.getByText('Level 1 · engineer')).toBeInTheDocument();
    expect(screen.getByText('EXP-2025-0002')).toBeInTheDocument();
    expect(screen.getByText('Level 2 · admin')).toBeInTheDocument();
  });

  it('offers approve, return and reject as three distinct decisions', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.getAllByRole('button', { name: 'Approve' })).not.toHaveLength(0);
    expect(screen.getAllByRole('button', { name: 'Return to fix' })).not.toHaveLength(0);
    expect(screen.getAllByRole('button', { name: 'Reject' })).not.toHaveLength(0);
  });

  it('sends the decision with the remarks typed against that row', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    const remarks = screen.getAllByRole('textbox', { name: 'Remarks' })[0]!;
    await user.type(remarks, 'Checked against the challan');
    await user.click(screen.getAllByRole('button', { name: 'Approve' })[0]!);

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/expenses/e1/approve');
    // The allocation rides along with the decision, because they are one act. Untouched, it
    // is whatever the head proposed and the row already carries.
    expect(post.mock.calls[0]![1]).toEqual({
      action: 'APPROVE',
      remarks: 'Checked against the challan',
      allocation: 'SITE',
    });
  });

  it('sends a return as its own action, not as a rejection', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    await user.click(screen.getAllByRole('button', { name: 'Return to fix' })[0]!);
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({ action: 'RETURN' });
  });

  /** A refusal decides nothing about a cost that is not being incurred. */
  it('leaves the allocation off a return', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    await user.click(screen.getAllByRole('button', { name: 'Return to fix' })[0]!);
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).not.toHaveProperty('allocation');
  });

  /**
   * One bill, two costs. The site's part is typed and the company carries the rest — and the
   * approval is held until the number is a real split, because the server refuses one that is
   * the whole bill or none of it and a 422 at that point reads as a broken screen.
   */
  it('sends a split with the site’s part, and will not send an empty one', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0001');

    await user.click(screen.getAllByRole('combobox', { name: 'Whose cost is it' })[0]!);
    await user.click(await screen.findByRole('option', { name: 'Part each' }));

    expect(screen.getAllByRole('button', { name: 'Approve' })[0]!).toBeDisabled();

    await user.type(screen.getAllByRole('spinbutton', { name: "The site's part" })[0]!, '1500');
    await user.click(screen.getAllByRole('button', { name: 'Approve' })[0]!);

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({
      action: 'APPROVE',
      allocation: 'SPLIT',
      siteShare: 1500,
    });
  });

  /** An engineer approves and never pays. The payment half simply is not there for him. */
  it('hides the payment half from somebody who cannot record payments', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.queryByText('Approved and still owed')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument();
  });

  /** The three figures on the row, and a payment capped at what is actually owed. */
  it('shows approved, paid and owed separately and will not overpay', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup({ delay: null });
    renderPage();

    expect(await screen.findByText('EXP-2025-0003')).toBeInTheDocument();
    expect(screen.getByText(/approved ₹50,000/)).toBeInTheDocument();
    expect(screen.getByText(/paid ₹20,000/)).toBeInTheDocument();
    expect(screen.getByText(/owed ₹30,000/)).toBeInTheDocument();

    const amount = screen.getByRole('spinbutton', { name: 'Pay now' });
    await user.type(amount, '40000');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeDisabled();

    await user.clear(amount);
    await user.type(amount, '30000');
    expect(screen.getByRole('button', { name: 'Record payment' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Record payment' }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/payments');
    expect(post.mock.calls[0]![1]).toMatchObject({ expenseId: 'e3', amount: 30000 });
  });
  /**
   * The approver is agreeing to a figure he did not watch being incurred, and who typed it is
   * half of what he is weighing.
   */
  it('names who typed each waiting expense', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.getByText('Typed by Uttam Singh')).toBeInTheDocument();
  });

  /** A user since removed from the rolls is admitted, never left as a blank line. */
  it('says so when the author is not on the rolls', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0002');

    expect(screen.getByText('Typed by somebody no longer on the rolls')).toBeInTheDocument();
  });

  /**
   * A bill number is not evidence. The picture is asked for per attachment and shown where the
   * decision is being made, so the challan photographed instead of the invoice is caught here
   * rather than after approval.
   */
  it('shows the photographed bill beside the decision', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    const bill = await screen.findByAltText('bill-ss-856.jpg');
    expect(bill).toHaveAttribute('src', 'https://minio.local/signed/att-1');
    expect(get).toHaveBeenCalledWith('/attachments/att-1/url');
  });

  /** Nothing attached is a fact the approver weighs, not an empty space on the card. */
  it('says when no bill was photographed', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0002');

    expect(screen.getAllByText('No photograph of the bill')).not.toHaveLength(0);
  });
});

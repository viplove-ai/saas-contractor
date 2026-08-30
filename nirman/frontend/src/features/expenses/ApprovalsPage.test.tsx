import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApprovalsPage } from './ApprovalsPage';
import type {
  Expense,
  ExpenseWorkflow,
  FloatBalance,
  PageResponse,
  Payment,
  Site,
} from './types';

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

/** The same page envelope, for the payment rows behind a part-paid bill. */
function page2(content: Payment[]): PageResponse<Payment> {
  return {
    content,
    page: 0,
    size: 50,
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
    refundableAmount: 0,
    refundedAmount: 0,
    writtenOffAmount: 0,
    outstandingDeposit: 0,
    depositStatus: 'NONE',
    spentAmount: 4000,
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

/** The ₹20,000 already gone out against EXP-2025-0003, with the screenshot that proved it. */
const PAYMENTS: Payment[] = [
  {
    id: 'pay-1',
    paymentNumber: 'PAY-2025-0007',
    expenseId: 'e3',
    paymentDate: '2025-06-10',
    amount: 20000,
    paymentMode: 'BANK',
    referenceNumber: 'UTR4471',
    attachments: [
      {
        id: 'pa-1',
        attachmentId: 'att-pay',
        docType: 'SCREENSHOT',
        fileName: 'upi-4471.jpg',
        contentType: 'image/jpeg',
      },
    ],
  },
];

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

/**
 * Two men carrying site cash: one with money left, one already out of pocket.
 *
 * <p>Both are in the fixture on purpose. The screen's whole job on a negative balance is to
 * say "owed to him" rather than to draw a minus sign, and a fixture with only positive rows
 * would never exercise the sentence that matters.</p>
 */
const FLOATS: FloatBalance[] = [
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
    if (url === '/attachments/att-pay/url') {
      return Promise.resolve({
        data: { url: 'https://minio.local/signed/att-pay', fileName: 'upi-4471.jpg' },
      });
    }
    // What has already gone out against a part-paid bill, and what proved it.
    if (url === '/payments') return Promise.resolve({ data: page2(PAYMENTS) });
    if (url === '/advances/float-balances') return Promise.resolve({ data: FLOATS });
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
   * A reference number is not a receipt.
   *
   * <p>Until V45 the register could hold twelve digits and nothing else, so a supplier
   * disputing a payment nine months later was answered with a string and the hope that
   * somebody's phone still had the screenshot on it. The file is uploaded first and the
   * payment claims it, in that order: a payment pointing at a file that does not exist is a
   * receipt nobody can produce.</p>
   */
  it('uploads the proof first, then records the payment against it', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup({ delay: null });
    post.mockImplementation((url: string) =>
      url === '/attachments'
        ? Promise.resolve({ data: { id: 'att-new' } })
        : Promise.resolve({ data: { id: 'pay-2' } }),
    );
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.type(screen.getByRole('spinbutton', { name: 'Pay now' }), '30000');
    await user.upload(
      screen.getByLabelText('Attach proof of payment'),
      new File([new Uint8Array(64)], 'upi-9912.jpg', { type: 'image/jpeg' }),
    );
    // The question only appears once there is something to describe by it.
    await user.click(await screen.findByRole('combobox', { name: 'What is it' }));
    await user.click(screen.getByRole('option', { name: 'Payment screenshot' }));

    await user.click(screen.getByRole('button', { name: 'Record payment' }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[0]![0]).toBe('/attachments');
    expect(post.mock.calls[1]![0]).toBe('/payments');
    expect(post.mock.calls[1]![1]).toMatchObject({
      expenseId: 'e3',
      amount: 30000,
      attachmentId: 'att-new',
      proofType: 'SCREENSHOT',
    });
  });

  /**
   * Cash handed across a table has nothing to photograph, and refusing the payment for want
   * of a picture is how a real payment ends up in a second book.
   */
  it('records a payment with no proof at all', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.type(screen.getByRole('spinbutton', { name: 'Pay now' }), '5000');
    await user.click(screen.getByRole('button', { name: 'Record payment' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/payments');
    expect(post.mock.calls[0]![1]).toMatchObject({ amount: 5000, attachmentId: undefined });
  });

  /**
   * Evidence that can be attached and never looked at is a filing cabinet nobody has the key
   * to — and the moment it is wanted is the moment somebody is disputing the payment.
   */
  it('shows what proved the money that has already gone out', async () => {
    permissions = ['expense:read', 'payment:record'];
    renderPage();
    await screen.findByText('EXP-2025-0003');

    expect(await screen.findByText(/UTR4471/)).toBeInTheDocument();
    expect(await screen.findByRole('img', { name: 'upi-4471.jpg' })).toBeInTheDocument();
  });
  /**
   * The approver is agreeing to a figure he did not watch being incurred, and who sent it is
   * half of what he is weighing.
   */
  it('names who submitted each waiting expense', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.getByText('Submitted by Uttam Singh')).toBeInTheDocument();
  });

  /**
   * A challan with no serial number on it is still a challan, and the man at the gate
   * photographed it.
   *
   * <p>The row used to say "No bill" whenever there was no bill <b>number</b>, which was
   * flatly untrue with the picture on the screen directly underneath the sentence denying it.
   * {@code ExpenseEvidencePolicy} has counted a photograph as evidence since V40 moved the
   * rule out of a check constraint precisely so it could see the attachments; this screen was
   * the last place still pretending otherwise.</p>
   */
  it('does not call a photographed bill "No bill" for want of a number on it', async () => {
    const photographed = expense({
      id: 'e4',
      expenseNumber: 'EXP-2025-0004',
      workflowStatus: 'SUBMITTED',
      pendingLevel: 1,
      pendingWithRole: 'ENGINEER',
      attachments: [
        {
          id: 'ea-4',
          attachmentId: 'att-1',
          docType: 'BILL',
          fileName: 'challan.jpg',
          contentType: 'image/jpeg',
          sizeBytes: 190_000,
        },
      ],
    });
    get.mockImplementation((url: string, config?: { params?: { status?: ExpenseWorkflow } }) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/approvals/pending') return Promise.resolve({ data: [] });
      if (url === '/attachments/att-1/url') {
        return Promise.resolve({
          data: { url: 'https://minio.local/signed/att-1', fileName: 'challan.jpg' },
        });
      }
      if (url === '/expenses') {
        return Promise.resolve({
          data: page(config?.params?.status === 'SUBMITTED' ? [photographed] : []),
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });

    renderPage();
    await screen.findByText('EXP-2025-0004');

    expect(screen.getByText('Bill photographed, no bill number on it')).toBeInTheDocument();
    expect(screen.queryByText('No bill')).not.toBeInTheDocument();
  });

  /** With nothing attached and no reason given, "No bill" is finally true. */
  it('still says No bill when there is neither a number nor a photograph', async () => {
    permissions = ['expense:read', 'expense:approve:l1'];
    renderPage();
    await screen.findByText('EXP-2025-0002');

    expect(screen.getByText('No bill')).toBeInTheDocument();
  });

  /** A user since removed from the rolls is admitted, never left as a blank line. */
  it('says so when the author is not on the rolls', async () => {
    renderPage();
    await screen.findByText('EXP-2025-0002');

    expect(
      screen.getByText('Submitted by somebody no longer on the rolls'),
    ).toBeInTheDocument();
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

  // ---------------------------------------------------------------- settling from a float

  /**
   * The second way of settling, offered on the same card as the first. Separating them onto
   * two screens would mean the accountant chooses between them before he has seen the bill.
   */
  it('offers the bill to be charged to whoever actually paid it', async () => {
    permissions = ['expense:read', 'payment:record'];
    renderPage();
    await screen.findByText('EXP-2025-0003');

    expect(await screen.findByLabelText('Who paid it')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Charge .* to his float/ }),
    ).toBeInTheDocument();
  });

  /**
   * A negative balance is said in words. "−4,000" on a cash register reads as a mistake to
   * everyone who has not been told otherwise, and the fact it carries — that the company owes
   * this man money — is the one that needs acting on.
   */
  it('says a man is owed money rather than drawing him a minus sign', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.click(await screen.findByLabelText('Who paid it'));
    expect(
      await screen.findByRole('option', { name: /Ramesh Bisht — owed ₹4,000/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: /Vivek Aggarwal — holding ₹12,000/ }),
    ).toBeInTheDocument();
  });

  /**
   * What the charge will do, before it is taken. Charging ₹30,000 to a man carrying ₹12,000
   * leaves the company owing him ₹18,000, which is correct, ordinary, and exactly what a lorry
   * at a gate produces — and reads as a bug if nobody says it first.
   */
  it('spells out that charging past a float leaves the company owing him', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.click(await screen.findByLabelText('Who paid it'));
    await user.click(
      await screen.findByRole('option', { name: /Vivek Aggarwal — holding ₹12,000/ }),
    );

    expect(
      await screen.findByText(
        'He is carrying ₹12,000.00, so this leaves the company owing him ₹18,000.00.',
      ),
    ).toBeInTheDocument();
  });

  /**
   * And the same rule on the other side of zero. A man already out of pocket is the case where
   * a raw "-₹4,000" is most likely to be read as a bug, because the reader is being asked to
   * act on it.
   */
  it('says a holder is already owed rather than printing him a minus sign', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.click(await screen.findByLabelText('Who paid it'));
    await user.click(
      await screen.findByRole('option', { name: /Ramesh Bisht — owed ₹4,000/ }),
    );

    expect(
      await screen.findByText(
        'He is already owed ₹4,000.00, so this leaves the company owing him ₹34,000.00.',
      ),
    ).toBeInTheDocument();
  });

  /** It names the man and charges the whole of what is left owing — there is no amount box. */
  it('charges the whole payable amount to the chosen holder', async () => {
    permissions = ['expense:read', 'payment:record'];
    const user = userEvent.setup();
    post.mockResolvedValue({ data: { id: 'pay-2' } });
    renderPage();
    await screen.findByText('EXP-2025-0003');

    await user.click(await screen.findByLabelText('Who paid it'));
    await user.click(
      await screen.findByRole('option', { name: /Vivek Aggarwal — holding ₹12,000/ }),
    );
    await user.click(screen.getByRole('button', { name: /Charge ₹30,000/ }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/expenses/e3/charge-to-float',
        expect.objectContaining({ holderUserId: 'u-vivek' }),
      ),
    );
    // No amount on the request: the charge is the whole of what is still owed.
    expect(post.mock.calls[0]?.[1]).not.toHaveProperty('amount');
  });

  /** The engineer approves and never pays, so neither way of settling is his to choose. */
  it('shows an approver no way to settle a bill', async () => {
    permissions = ['expense:read', 'expense:approve:l1'];
    renderPage();
    await screen.findByText('EXP-2025-0001');

    expect(screen.queryByLabelText('Who paid it')).not.toBeInTheDocument();
    expect(get).not.toHaveBeenCalledWith('/advances/float-balances', expect.anything());
  });
});

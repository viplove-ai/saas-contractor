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

/** One expense the office has sent back, as the list under the form returns it. */
function returnedExpense(): PageResponse<Expense> {
  const expense = {
    id: 'e-7',
    expenseNumber: 'EXP-2025-0042',
    siteId: 'site-a',
    expenseDate: '2025-06-04',
    categoryId: 'cat-site',
    categoryName: 'Site Expenses',
    description: 'Cartage',
    billNumber: 'SS/856',
    amountBeforeTax: 4000,
    gstPercent: 18,
    gstAmount: 720,
    totalAmount: 4720,
    paymentStatus: 'UNPAID',
    paidAmount: 0,
    payableAmount: 4720,
    workflowStatus: 'RETURNED',
    rejectionReason: 'Bill number does not match the challan',
    version: 4,
    attachments: [],
  } as unknown as Expense;
  return { ...NO_EXPENSES, content: [expense], totalElements: 1, totalPages: 1 };
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
   * A returned expense with nowhere to go is the shape of the bug this answers: the office
   * says the figure is wrong, and the only button on the row sends the same wrong figure back.
   * The reason has to be on screen — two days later nobody remembers it — and saving has to
   * resubmit, because a correction left as a draft is a returned expense nobody sees again.
   */
  it('corrects a returned expense in place and sends it straight back for approval', async () => {
    mockGets(returnedExpense());
    put.mockResolvedValue({ data: { id: 'e-7', siteId: 'site-a' } });
    post.mockResolvedValue({ data: { id: 'e-7', workflowStatus: 'SUBMITTED' } });
    const user = userEvent.setup({ delay: null });
    renderPage();

    // The site list and the expense list are two queries, and the row only appears once the
    // second lands. Waiting on the first alone is what made this flake under a loaded runner.
    await screen.findByRole('combobox', { name: 'Site' });
    await user.click(
      await screen.findByRole('button', { name: 'Correct it' }, { timeout: 5000 }),
    );

    // The office's words, on the form that answers them — and still on the row below, which
    // is why there are two of them.
    expect(screen.getAllByText('Bill number does not match the challan')).toHaveLength(2);
    expect(screen.getByText(/the office sent this back to be fixed/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Correct the expense' })).toBeInTheDocument();
    // The typed values are there to be corrected rather than re-entered from the paper.
    expect(screen.getByRole('textbox', { name: 'What was it for' })).toHaveValue('Cartage');

    await user.clear(screen.getByRole('textbox', { name: 'Bill number' }));
    await user.type(screen.getByRole('textbox', { name: 'Bill number' }), 'SS/902');
    await user.click(screen.getByRole('button', { name: 'Save and send for approval' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [string, { billNumber: string; version: number }];
    expect(url).toBe('/expenses/e-7');
    expect(body.billNumber).toBe('SS/902');
    // Carried so that two people correcting one row is a 409 rather than a silent overwrite.
    expect(body.version).toBe(4);

    // Saved and sent in one act, which is the half that stops it sitting as a draft.
    await waitFor(() => expect(post).toHaveBeenCalledWith('/expenses/e-7/submit'));
  });

  /**
   * "There is no bill" is one of the two things an expense actually comes back for, so the
   * photograph has to be attachable on the correction and not only when the expense is first
   * booked. Three calls in one act, and the order is the one the offline drain uses: the file
   * into storage, the link onto the record, then the record back into the queue.
   */
  it('attaches a bill photograph to the correction before sending it back', async () => {
    mockGets(returnedExpense());
    put.mockResolvedValue({ data: { id: 'e-7', siteId: 'site-a' } });
    post.mockImplementation((url: string) =>
      url === '/attachments'
        ? Promise.resolve({ data: { id: 'att-3' } })
        : Promise.resolve({ data: { id: 'e-7' } }),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByRole('combobox', { name: 'Site' });
    await user.click(await screen.findByRole('button', { name: 'Correct it' }, { timeout: 5000 }));

    // Small enough that the compressor hands it straight back, which is what jsdom can run.
    const bill = new File([new Uint8Array(64)], 'bill.jpg', { type: 'image/jpeg' });
    await user.upload(screen.getByLabelText('Photograph the bill'), bill);
    await user.click(screen.getByRole('button', { name: 'Save and send for approval' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    await waitFor(() =>
      expect(post.mock.calls.map((call) => call[0])).toEqual([
        '/attachments',
        '/expenses/e-7/attachments',
        '/expenses/e-7/submit',
      ]),
    );
    // The bucket has to know whose site the file belongs to, or the signed-URL read refuses it.
    expect(post.mock.calls[0]?.[2]).toMatchObject({
      params: { ownerEntityType: 'EXPENSE', kind: 'BILL', siteId: 'site-a' },
    });
    expect(post.mock.calls[1]?.[1]).toMatchObject({ attachmentId: 'att-3', docType: 'BILL' });
  });

  /** An expense does not move site: the site is what scoped every approval taken on it. */
  it('will not let a correction change the site', async () => {
    mockGets(returnedExpense());
    const user = userEvent.setup({ delay: null });
    renderPage();

    // The site list and the expense list are two queries, and the row only appears once the
    // second lands. Waiting on the first alone is what made this flake under a loaded runner.
    await screen.findByRole('combobox', { name: 'Site' });
    await user.click(
      await screen.findByRole('button', { name: 'Correct it' }, { timeout: 5000 }),
    );

    expect(screen.getByRole('combobox', { name: 'Site' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
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

  /**
   * The deposit on a connection.
   *
   * <p>₹18,000 leaves the bank and the board keeps ₹12,000 of it against the meter. The
   * vendor is still owed all of it; what the site spent is ₹6,000, and the ₹12,000 has to go
   * somewhere that will chase it — the failure it replaces is a job overstated by twice what
   * it paid for, and a refund arriving eight months later with nowhere to land.</p>
   */
  it('carves a refundable deposit out of the bill and says what the work cost', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
    await user.type(
      screen.getByRole('textbox', { name: 'What was it for' }),
      'Electricity connection',
    );
    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '18000');

    // Off by default: almost no bill has one, and a box on every ₹200 of cartage is a box
    // that gets a zero typed into it out of habit.
    expect(
      screen.queryByRole('spinbutton', { name: 'Refundable amount' }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Part of this comes back to us' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Refundable amount' }), '12000');

    expect(await screen.findByText(/this bill costs the work ₹6,000/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { refundableAmount: number }];
    expect(body.refundableAmount).toBe(12000);
  });

  /** A deposit larger than the bill it came on is a figure typed the wrong way round. */
  it('refuses a deposit bigger than the bill', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
    await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Connection');
    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '4500');
    await user.click(screen.getByRole('checkbox', { name: 'Part of this comes back to us' }));
    await user.type(screen.getByRole('spinbutton', { name: 'Refundable amount' }), '12000');

    expect(await screen.findByText(/cannot be more than ₹4,500/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save as draft' })).toBeDisabled();
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

  /*
    "Send it when you are ready" is usually now, and the row it meant had scrolled out of
    sight under the recent list. The same act, on the same expense, where the person is
    looking — booking and sending stay two presses, which is the part that matters.
  */
  it('sends the draft it has just booked, from the banner that says it saved', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Site Expenses' }));
    await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Cartage');
    await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '4000');
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    expect(await screen.findByText(/Saved EXP-2025-0001 as a draft/)).toBeInTheDocument();
    post.mockResolvedValueOnce({ data: { id: 'e1', workflowStatus: 'SUBMITTED' } });
    await user.click(screen.getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[1]![0]).toBe('/expenses/e1/submit');
    // And it stops offering to send the same one twice.
    expect(await screen.findByText(/EXP-2025-0001 has gone for approval/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send for approval' })).not.toBeInTheDocument();
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
    // It offers the other answer instead: re-open it, which keeps the number and asks for a
    // fresh approval rather than giving the bill a second one through a void.
    expect(screen.getByRole('button', { name: 'Re-open to correct' })).toBeInTheDocument();
  });

  /**
   * The figure typed badly and already signed. The row keeps its number — that is the whole
   * difference from a void — and the reason is required, because the approver is being asked
   * to sign the same expense a second time and cannot act on "it changed".
   */
  it('re-opens an approved expense, and will not do it without a reason', async () => {
    const approved: Expense = {
      id: 'e8',
      expenseNumber: 'EXP-2025-0008',
      siteId: 'site-a',
      expenseDate: '2025-06-02',
      categoryId: 'cat-site',
      description: 'Scaffolding hire',
      amountBeforeTax: 45000,
      gstPercent: 0,
      gstAmount: 0,
      totalAmount: 45000,
      paymentStatus: 'UNPAID',
      paidAmount: 0,
      payableAmount: 45000,
      refundableAmount: 0,
      refundedAmount: 0,
      writtenOffAmount: 0,
      outstandingDeposit: 0,
      depositStatus: 'NONE',
      spentAmount: 45000,
      costAllocation: 'SITE',
      siteCost: 45000,
      companyCost: 0,
      revision: 0,
      workflowStatus: 'APPROVED',
      version: 3,
      attachments: [],
    };
    mockGets({ ...NO_EXPENSES, totalElements: 1, content: [approved] });
    post.mockResolvedValue({ data: { ...approved, workflowStatus: 'SUBMITTED', revision: 1 } });

    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('EXP-2025-0008');

    await user.click(screen.getByRole('button', { name: 'Re-open to correct' }));
    const send = screen.getByRole('button', { name: 'Re-open and send for approval again' });
    expect(send).toBeDisabled();

    await user.type(
      screen.getByRole('textbox', { name: 'What was wrong with it' }),
      'typed 45,000 for 4,500',
    );
    const amount = screen.getByRole('spinbutton', { name: 'Amount before tax' });
    await user.clear(amount);
    await user.type(amount, '4500');
    await user.click(screen.getByRole('button', { name: 'Re-open and send for approval again' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/expenses/e8/revise');
    const body = post.mock.calls[0]![1] as { reason: string; amountBeforeTax: number; version: number };
    expect(body.reason).toBe('typed 45,000 for 4,500');
    expect(body.amountBeforeTax).toBe(4500);
    // The version it was read at: the row may have moved, and this is the refusal that says so.
    expect(body.version).toBe(3);
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

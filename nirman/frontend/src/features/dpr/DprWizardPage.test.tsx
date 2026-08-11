import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DprWizardPage } from './DprWizardPage';
import type { BoqItem, Dpr, DprPrefill, DprWorkflow, Site } from './types';

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

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const BOQ_ITEMS: BoqItem[] = [
  {
    id: 'boq-brick',
    projectId: 'p1',
    itemNumber: 'B-003',
    description: 'Brickwork 1:4 in superstructure',
    synthetic: false,
  },
];

/** The seeded 10 June: three men on the muster, unverified, and a cartage bill. */
function prefill(overrides: Partial<DprPrefill> = {}): DprPrefill {
  return {
    siteId: 'site-a',
    siteName: 'Kausani Main Block',
    reportDate: '2025-06-10',
    reportExists: false,
    outsourcedLabour: { enabled: false, headCount: 0, lines: [] },
  labour: {
      presentCount: 3,
      absentCount: 0,
      regularHours: 21,
      overtimeHours: 1,
      cost: 1875,
      unverifiedCost: 1875,
      recordCount: 3,
      unverifiedCount: 3,
      lines: [
        {
          skillCategoryName: 'Mason',
          labourContractorName: 'Nainital Labour Suppliers',
          headCount: 2,
          regularHours: 14,
          overtimeHours: 1,
        },
      ],
    },
    material: {
      receivedValue: 41000,
      consumedValue: 3200,
      receiptCount: 1,
      issueCount: 2,
      lines: [],
    },
    expense: {
      totalBooked: 1800,
      costIncurred: 1800,
      materialPurchases: 0,
      labourDisbursements: 0,
      expenseCount: 1,
      unapprovedCount: 1,
    },
    labourCostProvisional: true,
    suggestedWorkItems: [
      {
        boqItemId: 'boq-brick',
        itemNumber: 'B-003',
        description: 'Brickwork 1:4 in superstructure',
        because: 'material issued',
      },
    ],
    caveat: 'Material received is inventory; only material consumed is cost.',
    ...overrides,
  };
}

/** A draft left half-written: one work line, the day written up, nothing sent. */
function existingDraft(status: DprWorkflow = 'DRAFT'): Dpr {
  return {
    id: 'dpr-existing',
    dprNumber: 'DPR-2025-0006',
    siteId: 'site-a',
    siteName: 'Kausani Main Block',
    projectId: 'p1',
    reportDate: '2025-06-10',
    workflowStatus: status,
    snapshotFrozen: status !== 'DRAFT',
    workSummary: 'Brickwork carried up to sill level',
    version: 3,
    workItems: [
      {
        id: 'wi-1',
        activity: 'Brickwork on the north wall',
        sortOrder: 0,
        measured: false,
      },
    ],
    labour: [],
    machinery: [],
    photos: [],
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DprWizardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(data: DprPrefill = prefill(), report: Dpr = existingDraft()) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/boq-items') return Promise.resolve({ data: BOQ_ITEMS });
    if (url === '/dprs/prefill') return Promise.resolve({ data });
    if (url === `/dprs/${report.id}`) return Promise.resolve({ data: report });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('DprWizardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGets();
    post.mockResolvedValue({
      data: { id: 'dpr-1', dprNumber: 'DPR-2025-0007', workflowStatus: 'DRAFT', version: 1 },
    });
  });

  /**
   * The reason the screen exists. A supervisor confirms what the records already say instead
   * of copying figures he has entered once already.
   */
  it('opens on what the records already say about the day', async () => {
    renderPage();

    expect(await screen.findByText('Labour')).toBeInTheDocument();
    // The figure under its own label, rather than a bare '3' the stepper also renders.
    expect(screen.getByText('Present').nextSibling).toHaveTextContent('3');
    expect(screen.getByText('21.00 h')).toBeInTheDocument();
    expect(screen.getByText('Mason')).toBeInTheDocument();
  });

  /**
   * The wage is frozen at verification, so a cost standing on unverified attendance can move
   * overnight without anybody editing anything. A report that did not say so would be quoting
   * a number it cannot stand behind.
   */
  it('says so when the labour cost is still provisional', async () => {
    renderPage();

    expect(
      await screen.findByText(/3 attendance row\(s\) behind this are not verified/),
    ).toBeInTheDocument();
  });

  /** Received is inventory and consumed is cost. Blending them counts the cement twice. */
  it('keeps material received apart from material consumed', async () => {
    renderPage();

    expect(await screen.findByText('Received (inventory)')).toBeInTheDocument();
    expect(screen.getByText('Consumed (cost)')).toBeInTheDocument();
  });

  /** Four figures on the cash tile, and the caveat that stops anybody adding the wrong one. */
  it('shows cost incurred apart from total booked, with the caveat', async () => {
    renderPage();

    expect(await screen.findByText('Cost incurred')).toBeInTheDocument();
    expect(screen.getByText('Material purchases')).toBeInTheDocument();
    expect(screen.getByText('Wage payments')).toBeInTheDocument();
    expect(
      screen.getByText('Material received is inventory; only material consumed is cost.'),
    ).toBeInTheDocument();
  });

  /**
   * A suggestion is evidence somebody worked on a line, not a measurement of how much got
   * built — so it lands as a row to fill in rather than as a claim.
   */
  it('turns a suggested work item into a line without claiming a quantity', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(await screen.findByText(/B-003 · material issued/));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    const activity = await screen.findByRole('textbox', { name: 'What was done' });
    expect(activity).toHaveValue('Brickwork 1:4 in superstructure');
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(null);
  });

  /**
   * A day already covered by a draft is the common case, not an error: the supervisor started
   * the report at lunchtime and came back to it. He is asked which of the two things he means,
   * and nothing moves until he says.
   */
  it('offers to carry on with the draft that already covers the day', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(prefill({ reportExists: true, existingDprId: 'dpr-existing' }));
    renderPage();

    expect(await screen.findByText(/DPR-2025-0006 already covers this day/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Carry on with it' }));

    // What was in the draft is what he is now editing, rather than a blank form that would
    // overwrite it with nothing.
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('textbox', { name: 'What was done' })).toHaveValue(
      'Brickwork on the north wall',
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('textbox', { name: 'What happened today' })).toHaveValue(
      'Brickwork carried up to sill level',
    );
  });

  /**
   * Starting again writes over the same report. One report per site per day is the server's
   * rule, so "fresh" cannot mean a second row — and the PUT rather than a POST is what proves
   * the screen is not quietly trying for one.
   */
  it('starts fresh over the same report rather than opening a second one', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(prefill({ reportExists: true, existingDprId: 'dpr-existing' }));
    put.mockResolvedValue({
      data: { id: 'dpr-existing', dprNumber: 'DPR-2025-0006', workflowStatus: 'DRAFT', version: 4 },
    });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Start fresh' }));
    // Asked twice, because it is the one button that throws away somebody's typing.
    await user.click(screen.getByRole('button', { name: 'Yes, clear it and start again' }));

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('textbox', { name: 'What was done' })).toHaveValue('');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(
      screen.getByRole('textbox', { name: 'What happened today' }),
      'Rain until noon, then curing',
    );
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(post).not.toHaveBeenCalled();
    expect(put.mock.calls[0]![0]).toBe('/dprs/dpr-existing');
    expect(put.mock.calls[0]![1]).toMatchObject({
      workSummary: 'Rain until noon, then curing',
      workItems: [],
      version: 3,
    });
  });

  /** A signed report is the document that was signed. Neither answer is offered for one. */
  it('offers neither answer once the day’s report has been sent', async () => {
    mockGets(
      prefill({ reportExists: true, existingDprId: 'dpr-existing' }),
      existingDraft('SUBMITTED'),
    );
    renderPage();

    expect(await screen.findByText(/can no longer be edited/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start fresh' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  /** Saving and sending are two acts. A draft is not a report anybody has stood behind. */
  it('saves a draft without sending it for signature', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(
      await screen.findByRole('textbox', { name: 'What was done' }),
      'Brickwork on the north wall',
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/dprs');
    expect(post.mock.calls[0]![1]).toMatchObject({
      siteId: 'site-a',
      workItems: [{ activity: 'Brickwork on the north wall' }],
    });
  });

  /**
   * The id is generated on the device, which is what makes an offline re-send one report
   * rather than three.
   */
  it('generates the report id on the device', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(await screen.findByRole('textbox', { name: 'What was done' }), 'Curing');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toHaveProperty('id', expect.stringMatching(/^[0-9a-f-]{36}$/));
  });

  /** Sending is save-then-submit, so a report never reaches the engineer half-written. */
  it('saves before it sends for signature', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(await screen.findByRole('textbox', { name: 'What was done' }), 'Beam steel');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Send for signature' }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[0]![0]).toBe('/dprs');
    expect(post.mock.calls[1]![0]).toBe('/dprs/dpr-1/submit');
  });

  /** A blank starter row is not a work item and must not be sent as one. */
  it('does not send an empty work line', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.type(
      await screen.findByRole('textbox', { name: 'What happened today' }),
      'Site cleared and levelled',
    );
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({ workItems: [] });
  });
});

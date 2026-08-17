import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DprWizardPage } from './DprWizardPage';
import type { BoqItem, Dpr, DprPrefill, DprWorkflow, Site } from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const del = vi.fn();

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
      delete: (...args: unknown[]) => del(...args),
    },
  };
});

/** A supervisor: he records what the day was, and may name a material at the gate. */
const SUPERVISOR = ['dpr:draft', 'dpr:delete', 'inventory:receive', 'masterdata:provisional'];
/** The engineer, who owns the half of the report that claims work — and the signature. */
const ENGINEER = [...SUPERVISOR, 'dpr:verify'];

/**
 * Who is looking at the screen, set per test.
 *
 * <p>The wizard is two screens now, and which one it is turns entirely on {@code dpr:verify}.
 * Read at render rather than at import, so a test can change it.</p>
 */
let permissions: string[] = SUPERVISOR;

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-sup', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

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
    outsourcedLabour: { enabled: false, headCount: 0, manHours: 0, lines: [] },
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
          labourSupplierName: 'Nainital Labour Suppliers',
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

/** A day where nothing at all was recorded — no muster, no material, no bill. */
function quietDay(overrides: Partial<DprPrefill> = {}): DprPrefill {
  const base = prefill(overrides);
  return {
    ...base,
    labour: { ...base.labour, presentCount: 0, recordCount: 0, unverifiedCount: 0, lines: [] },
    material: { ...base.material, receiptCount: 0, issueCount: 0, lines: [] },
    expense: { ...base.expense, expenseCount: 0, unapprovedCount: 0 },
    labourCostProvisional: false,
    suggestedWorkItems: [],
    ...overrides,
  };
}

/** A draft left half-written: one work line, the day written up, nothing sent. */
function existingDraft(status: DprWorkflow = 'DRAFT', overrides: Partial<Dpr> = {}): Dpr {
  return {
    id: 'dpr-existing',
    dprNumber: 'DPR-2025-0006',
    siteId: 'site-a',
    siteName: 'Kausani Main Block',
    projectId: 'p1',
    reportDate: '2025-06-10',
    siteOperational: true,
    workflowStatus: status,
    snapshotFrozen: status !== 'DRAFT',
    instructionsReceived: 'The junior engineer asked for the sill course to be re-checked',
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
    ...overrides,
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
    permissions = SUPERVISOR;
    mockGets();
    post.mockResolvedValue({
      data: {
        id: 'dpr-1',
        dprNumber: 'DPR-2025-0007',
        workflowStatus: 'DRAFT',
        siteOperational: true,
        version: 1,
        photos: [],
      },
    });
  });

  // ---------------------------------------------------------------- the day so far

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
    // The trade breakdown draws a table and a card list at once, so scope to one.
    expect(
      within(screen.getByRole('table', { name: 'Labour on site' })).getByText('Mason'),
    ).toBeInTheDocument();
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

  // ---------------------------------------------------------------- two halves, two people

  /**
   * The supervisor records what the day was and hands it over. Work done is not his to write:
   * a quantity there becomes a claim against the contract when the report is signed, and the
   * man who measures it should be the man who stands behind it.
   */
  it('gives the supervisor the day, and no work step at all', async () => {
    renderPage();

    await screen.findByText('Labour');
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
    expect(screen.queryByText('Work done')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Submit' }),
    ).toBeInTheDocument();
  });

  /**
   * A supervisor's save carries no work items at all — not an empty list.
   *
   * <p>The difference is a report the engineer wrote on and sent back: an empty list is an
   * instruction to delete his lines, and pressing Save on a screen that never showed them is
   * not an instruction to do anything of the sort.</p>
   */
  it('does not send the engineer’s half when the supervisor saves', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const body = post.mock.calls[0]![1] as Record<string, unknown>;
    expect(body).toMatchObject({ siteId: 'site-a', siteOperational: true });
    expect(body).not.toHaveProperty('workItems');
    expect(body).not.toHaveProperty('workSummary');
  });

  /**
   * The id is generated on the device, which is what makes an offline re-send one report
   * rather than three.
   */
  it('generates the report id on the device', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toHaveProperty('id', expect.stringMatching(/^[0-9a-f-]{36}$/));
  });

  /** Handing over is save-then-submit, so a report never reaches the engineer half-written. */
  it('saves before it hands the day over', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Submit' }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[0]![0]).toBe('/dprs');
    expect(post.mock.calls[1]![0]).toBe('/dprs/dpr-1/submit');
  });

  /**
   * A suggestion is evidence somebody worked on a line, not a measurement of how much got
   * built — so it lands as a row to fill in rather than as a claim. Offered to the engineer,
   * because the row it lands in is his.
   */
  it('turns a suggested work item into a line without claiming a quantity', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ENGINEER;
    renderPage();

    await user.click(await screen.findByText(/B-003 · material issued/));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    const activity = await screen.findByRole('textbox', { name: 'What was done' });
    expect(activity).toHaveValue('Brickwork 1:4 in superstructure');
    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(null);
  });

  /**
   * The other end of the handover. A submitted report is not unfinished by accident — it is
   * waiting on the engineer for the half only he can write — so he is not asked what to do
   * about it, and what the supervisor said is on screen and beyond his reach.
   */
  it('opens a handed-over report straight into the engineer’s half', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ENGINEER;
    mockGets(
      prefill({ reportExists: true, existingDprId: 'dpr-existing' }),
      existingDraft('SUBMITTED'),
    );
    put.mockResolvedValue({
      data: existingDraft('SUBMITTED', { version: 4 }),
    });
    post.mockResolvedValue({ data: existingDraft('VERIFIED', { version: 5 }) });
    renderPage();

    expect(await screen.findByText(/DPR-2025-0006 is waiting on you/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start fresh' })).not.toBeInTheDocument();
    // What the day was is frozen: it is the supervisor's statement about a day he stood on.
    expect(screen.getByRole('button', { name: 'No work today' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Sign the report' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    // The supervisor's half is not sent back at it — the server refuses it, and there is no
    // reason for the screen to try.
    const body = put.mock.calls[0]![1] as Record<string, unknown>;
    expect(body).not.toHaveProperty('siteOperational');
    expect(body).not.toHaveProperty('weather');
    expect(post.mock.calls[0]![0]).toBe('/dprs/dpr-existing/verify');
    expect(post.mock.calls[0]![1]).toMatchObject({ action: 'VERIFY' });
  });

  /** A signed report is the document that was signed, and it is nobody's to edit. */
  it('refuses the day once its report has been verified', async () => {
    permissions = ENGINEER;
    mockGets(
      prefill({ reportExists: true, existingDprId: 'dpr-existing' }),
      existingDraft('VERIFIED'),
    );
    renderPage();

    expect(await screen.findByText(/is no longer yours to edit/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start fresh' })).not.toBeInTheDocument();
  });

  /** A supervisor cannot write to a report he has handed over, either. */
  it('closes a handed-over report to the supervisor who sent it', async () => {
    mockGets(
      prefill({ reportExists: true, existingDprId: 'dpr-existing' }),
      existingDraft('SUBMITTED'),
    );
    renderPage();

    expect(await screen.findByText(/is no longer yours to edit/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
  });

  // ---------------------------------------------------------------- the day it did not work

  /**
   * The whole second flow. A day the site did not work is a different document, not an empty
   * one: a cause off the closed list, a note, and nothing else — because there is nothing
   * else. The cause is what a month's delay statement is counted from.
   */
  it('records a lost day as its cause, and asks for nothing else', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(quietDay());
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    await user.click(screen.getByRole('button', { name: 'No work today' }));

    // The figures and the entry cards go with the working day they described.
    expect(screen.queryByText('Received (inventory)')).not.toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: /Why the site did not work/ }));
    await user.click(screen.getByRole('option', { name: 'Weather' }));
    await user.type(
      screen.getByRole('textbox', { name: 'What happened' }),
      'River road under water from first light',
    );
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({
      siteOperational: false,
      nonOperationalCause: 'WEATHER',
      nonOperationalNote: 'River road under water from first light',
    });
  });

  /**
   * A lost day with no cause on it cannot be counted towards a claim for time, so the screen
   * will not let one be saved. The server refuses it too — this is the half that does not
   * cost him a round trip at the end of a day's typing.
   */
  it('will not save a lost day without a cause', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(quietDay());
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    await user.click(screen.getByRole('button', { name: 'No work today' }));

    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Submit' }),
    ).toBeDisabled();
  });

  /** "Other" says nothing on its own, so it is the one cause that also needs the sentence. */
  it('asks what happened when the cause is “other”', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(quietDay());
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    await user.click(screen.getByRole('button', { name: 'No work today' }));
    await user.click(screen.getByRole('combobox', { name: /Why the site did not work/ }));
    await user.click(screen.getByRole('option', { name: 'Other' }));

    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();

    await user.type(
      screen.getByRole('textbox', { name: /What happened/ }),
      'Land dispute at the gate',
    );
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled();
  });

  /**
   * The contradiction is shown and not refused. A muster with men on it behind a day recorded
   * as lost may be perfectly right — a watchman was there, a gang was paid to stand — and the
   * supervisor on the site is the one who knows which. The screen's job is to be sure he is
   * not saying it by accident.
   */
  it('warns when the records say something happened on a day recorded as lost', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    await user.click(screen.getByRole('button', { name: 'No work today' }));

    expect(
      await screen.findByText(/The records say something happened here today/),
    ).toBeInTheDocument();
    expect(screen.getByText(/3 man\/men marked present on the muster/)).toBeInTheDocument();
    expect(screen.getByText(/1 bill\(s\) booked/)).toBeInTheDocument();
  });

  /** Even for the engineer, a lost day has no work step: there is nothing to claim. */
  it('gives the engineer no work step on a lost day either', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ENGINEER;
    mockGets(quietDay());
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'No work today' }));
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------- the day already covered

  /**
   * A day already covered by a draft is the common case, not an error: the supervisor started
   * the report at lunchtime and came back to it. He is asked which of the two things he means,
   * and nothing moves until he says.
   */
  it('offers to carry on with the draft that already covers the day', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ENGINEER;
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
    expect(
      screen.getByRole('textbox', { name: /Instructions received from the department/ }),
    ).toHaveValue('The junior engineer asked for the sill course to be re-checked');
  });

  /**
   * Plant is on the day step now, with the rest of what the supervisor watched happen. It is
   * the one thing on that step that is not a claim and not derived from a record elsewhere.
   */
  it('puts plant on the supervisor’s step, and sends it as his half', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await screen.findByText('Labour');
    expect(screen.getByText('Plant on site')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add a machine' }));
    await user.type(await screen.findByRole('textbox', { name: 'Machine' }), 'Concrete mixer');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const body = post.mock.calls[0]![1] as Record<string, unknown>;
    expect(body).toMatchObject({ machinery: [expect.objectContaining({ machineryName: 'Concrete mixer' })] });
    // Still nothing of the engineer's, which is the half plant used to sit in.
    expect(body).not.toHaveProperty('workItems');
  });

  /** A day nobody worked has no plant hours either: the machines were where they were. */
  it('sends no plant on a lost day', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(quietDay());
    renderPage();

    await screen.findByText('Labour');   // the day has loaded; the site picker has settled
    await user.click(screen.getByRole('button', { name: 'Add a machine' }));
    await user.type(await screen.findByRole('textbox', { name: 'Machine' }), 'Concrete mixer');

    await user.click(screen.getByRole('button', { name: 'No work today' }));
    expect(screen.queryByText('Plant on site')).not.toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: /Why the site did not work/ }));
    await user.click(screen.getByRole('option', { name: 'Holiday' }));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![1]).toMatchObject({ siteOperational: false, machinery: [] });
  });

  /**
   * The observations step asks two questions now. The summary, the delays, the safety and
   * quality boxes and the note to the office are gone — four of them were prose answered with
   * "nil" every evening, and the fifth restated the work rows on the step before.
   */
  it('asks only for the department’s instructions and tomorrow’s plan', async () => {
    const user = userEvent.setup({ delay: null });
    permissions = ENGINEER;
    renderPage();

    await screen.findByText('Labour');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Next' }));

    expect(
      await screen.findByRole('textbox', { name: /Instructions received from the department/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Plan for tomorrow' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'What happened today' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Safety' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Quality' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Delays and stoppages' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Needs the office to act' })).not.toBeInTheDocument();
  });

  /** A resumed lost day comes back as a lost day, with the cause it was written with. */
  it('carries a lost day’s cause back into the form', async () => {
    const user = userEvent.setup({ delay: null });
    // No work lines on it, because that is what a lost day is.
    mockGets(
      prefill({ reportExists: true, existingDprId: 'dpr-existing' }),
      existingDraft('DRAFT', {
        siteOperational: false,
        nonOperationalCause: 'STRIKE',
        nonOperationalNote: 'Transport bandh across the district',
        workItems: [],
      }),
    );
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Carry on with it' }));

    expect(screen.getByRole('combobox', { name: /Why the site did not work/ })).toHaveTextContent(
      'Strike or bandh',
    );
    expect(screen.getByRole('textbox', { name: 'What happened' })).toHaveValue(
      'Transport bandh across the district',
    );
  });

  /**
   * The third answer to a day that already has a report, and the one starting fresh cannot
   * give: this report should not exist at all. Deleting hands the day back — an emptied
   * draft would still hold it against the one-per-site-per-day rule.
   */
  it('deletes the report that should never have been opened, and frees the day', async () => {
    const user = userEvent.setup({ delay: null });
    mockGets(prefill({ reportExists: true, existingDprId: 'dpr-existing' }));
    del.mockResolvedValue({ data: { id: 'dpr-existing' } });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Delete it' }));
    await user.type(
      screen.getByRole('textbox', { name: /Why is it being deleted/ }),
      'Wrong site — this was KSN-B',
    );
    await user.click(screen.getByRole('button', { name: 'Delete report' }));

    await waitFor(() => expect(del).toHaveBeenCalledOnce());
    expect(del.mock.calls[0]![0]).toBe('/dprs/dpr-existing');
    expect(del.mock.calls[0]![1]).toMatchObject({
      data: { reason: 'Wrong site — this was KSN-B' },
    });
    // Nothing was written to the report on the way past: deleting is not a save.
    expect(put).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
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
      data: existingDraft('DRAFT', { version: 4 }),
    });
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Start fresh' }));
    // Asked twice, because it is the one button that throws away somebody's typing.
    await user.click(screen.getByRole('button', { name: 'Yes, clear it and start again' }));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(post).not.toHaveBeenCalled();
    expect(put.mock.calls[0]![0]).toBe('/dprs/dpr-existing');
    expect(put.mock.calls[0]![1]).toMatchObject({ siteOperational: true, version: 3 });
  });
});

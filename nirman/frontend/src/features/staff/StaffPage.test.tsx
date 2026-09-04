import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StaffPage } from './StaffPage';
import type { SalaryRevision, StaffDashboard, StaffDocument, StaffProfile } from './types';

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

let permissions = ['staff:read', 'staff:write'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', fullName: 'Viplove Chaudhary', roles: ['ADMIN'], permissions,
      signatureAttachmentId: 'att-sig' },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

/** A member somebody filled in, and one nobody has touched since he was given a login. */
const STAFF: StaffProfile[] = [
  {
    userId: 'u-1',
    username: 'deepak',
    fullName: 'Deepak Joshi',
    mobile: '9812300011',
    active: true,
    roles: ['SUPERVISOR'],
    employmentType: 'PROBATION',
    pfApplicable: true,
    esiApplicable: true,
    pfOnFullWages: false,
    accommodationProvided: false,
    fuelProvided: false,
    joinedOn: '2026-02-01',
    probationDays: 90,
    probationMonthlySalary: 18000,
    confirmedMonthlySalary: 24000,
    probationEndsOn: '2026-05-02',
    probationOverdue: true,
    currentSalary: 18000,
    bankAccountNo: '50100012345678',
    bankIfsc: 'HDFC0001234',
    version: 3,
  },
  {
    userId: 'u-2',
    username: 'ramesh',
    fullName: 'Ramesh Negi',
    mobile: '9812300022',
    active: true,
    roles: ['SUPERVISOR'],
    employmentType: 'PERMANENT',
    pfApplicable: false,
    esiApplicable: false,
    pfOnFullWages: false,
    accommodationProvided: false,
    fuelProvided: false,
    probationOverdue: false,
  },
];

const DASHBOARD: StaffDashboard = {
  totalActive: 2,
  permanent: 1,
  contractual: 0,
  onProbation: 1,
  probationOverdue: 1,
  probationEndingSoon: 0,
  contractsEndingSoon: 0,
  missingBankDetails: 1,
  noRecordYet: 1,
  monthlyPayroll: 18000,
  attentionNeeded: [
    {
      userId: 'u-1',
      fullName: 'Deepak Joshi',
      reason: 'Probation ended and nobody has confirmed them',
      on: '2026-05-02',
    },
    {
      userId: 'u-2',
      fullName: 'Ramesh Negi',
      reason: 'No staff record yet — no address, no bank details, no terms',
    },
  ],
};

/** A scan the browser can draw, and a signed letter it cannot. */
const DOCUMENTS: StaffDocument[] = [
  {
    id: 'doc-1',
    userId: 'u-1',
    attachmentId: 'att-1',
    docType: 'AADHAAR',
    note: 'Front and back on one page',
    fileName: 'aadhaar.jpg',
    contentType: 'image/jpeg',
    image: true,
    uploadedAt: '2026-03-01T09:00:00Z',
  },
  {
    id: 'doc-2',
    userId: 'u-1',
    attachmentId: 'att-2',
    docType: 'APPOINTMENT',
    fileName: 'letter.pdf',
    contentType: 'application/pdf',
    image: false,
    uploadedAt: '2026-02-01T09:00:00Z',
  },
];

const SALARY: SalaryRevision[] = [
  {
    id: 's1',
    monthlyAmount: 18000,
    structured: true,
    basic: 15000,
    dearnessAllowance: 0,
    hra: 0,
    conveyance: 0,
    otherAllowance: 3000,
    statutoryWages: 15000,
    effectiveFrom: '2026-02-01',
    reason: 'Joined on probation',
  },
];

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <StaffPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('StaffPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['staff:read', 'staff:write'];
    get.mockImplementation((url: string) => {
      if (url === '/staff') return Promise.resolve({ data: STAFF });
      if (url === '/staff/dashboard') return Promise.resolve({ data: DASHBOARD });
      if (url === '/staff/u-1/salary') return Promise.resolve({ data: SALARY });
      if (url === '/staff/u-1/documents') return Promise.resolve({ data: DOCUMENTS });
      if (url.endsWith('/documents')) return Promise.resolve({ data: [] });
      if (url.startsWith('/attachments/')) {
        return Promise.resolve({ data: { url: 'blob:signed', fileName: 'aadhaar.jpg' } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: { id: 'att-9' } });
    put.mockResolvedValue({ data: {} });
    del.mockResolvedValue({ data: null });
  });

  it('names the payroll and the footing everybody is on', async () => {
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    expect(screen.getByText('On the payroll')).toBeInTheDocument();
    expect(screen.getByText('Payroll a month')).toBeInTheDocument();
    expect(screen.getAllByText('On probation').length).toBeGreaterThan(0);
  });

  /** The part that earns the screen: neither of these shows up anywhere else. */
  it('names the probation nobody closed and the member who cannot be paid', async () => {
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const alerts = screen.getByText('Needs attention').closest('div')!;
    expect(within(alerts).getByText(/Probation ended and nobody has confirmed them/)).toBeInTheDocument();
    expect(within(alerts).getByText(/No staff record yet/)).toBeInTheDocument();
  });

  it('marks a member nobody has filled in as having no record', async () => {
    renderPage();
    await screen.findAllByText('Ramesh Negi');

    const row = within(screen.getByRole('table')).getByText('ramesh').closest('tr')!;
    expect(within(row).getByText('No record yet')).toBeInTheDocument();
    // No salary is an em dash, not a zero: nobody has said what he is paid, and a zero
    // would read as somebody who works for nothing.
    expect(within(row).getAllByText('—')).toHaveLength(2);   // joined on, and paid a month
  });

  /**
   * The first save carries no version — there is no row yet — and the server reads its
   * absence as "create".
   */
  it('saves a first record with no version on it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Ramesh Negi');

    const row = within(screen.getByRole('table')).getByText('ramesh').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));

    expect(await screen.findByText(/Nothing on file for Ramesh Negi yet/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Permanent address'), 'Village Someshwar, Almora');
    await user.click(screen.getByRole('button', { name: 'Save the record' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [string, { version?: number; aadhaarLast4?: string }];
    expect(url).toBe('/staff/u-2');
    expect(body.version).toBeUndefined();
    // An empty box is "not given", not a blank Aadhaar stored as an Aadhaar.
    expect(body.aadhaarLast4).toBeUndefined();
  });

  /**
   * The two leaving fields are behind a tick, and the tick is off. Almost every visit to
   * this dialog is about a telephone number, and an open date box is what gets today typed
   * into it.
   */
  it('keeps the leaving fields shut until somebody says they are leaving', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await screen.findByLabelText('Aadhaar — last 4 digits');

    expect(screen.getByLabelText('Last day')).not.toBeVisible();

    await user.click(screen.getByLabelText('They are leaving, or have left'));
    const lastDay = screen.getByLabelText('Last day');
    await waitFor(() => expect(lastDay).toBeVisible());
    expect(lastDay).toHaveValue('');
  });

  /**
   * The papers behind the figures. Every box above them was typed off one of these, and the
   * documents themselves used to stay in a folder at head office.
   */
  it('shows a scan as a picture and a letter as something to open', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));

    // Drawn, because IMG_2026.jpg is the same nine characters whether it is the right man's
    // card or a thumb over the lens.
    expect(await screen.findByRole('img', { name: 'Aadhaar card' })).toBeInTheDocument();
    expect(screen.getByText('Front and back on one page')).toBeInTheDocument();
    // A PDF is offered rather than drawn: a broken image icon would say the file was lost.
    expect(screen.getByText('Appointment or contract letter')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Appointment or contract letter' })).toBeNull();
  });

  /**
   * What it is comes before the file. A register of eight scans all called "Something else"
   * answers none of the questions it exists for, and nobody goes back to relabel them.
   */
  it('will not take a paper until somebody says what it is', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await screen.findByRole('img', { name: 'Aadhaar card' });

    expect(screen.getByLabelText('From device')).toBeDisabled();
    expect(screen.getByLabelText('Photograph it')).toBeDisabled();
  });

  /** Two calls and one act: the file into storage, then the row that says whose it is. */
  it('files a paper under what it is, in two calls', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await screen.findByRole('img', { name: 'Aadhaar card' });

    await user.click(screen.getByLabelText('What it is'));
    await user.click(await screen.findByRole('option', { name: 'PAN card' }));
    await user.upload(
      screen.getByLabelText('From device'),
      new File([new Uint8Array(64)], 'pan.jpg', { type: 'image/jpeg' }),
    );

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    const [uploadUrl, , config] = post.mock.calls[0] as [
      string,
      FormData,
      { params: { ownerEntityType: string } },
    ];
    expect(uploadUrl).toBe('/attachments');
    expect(config.params.ownerEntityType).toBe('STAFF_DOCUMENT');

    const [rowUrl, body] = post.mock.calls[1] as [string, { docType: string; attachmentId: string }];
    expect(rowUrl).toBe('/staff/u-1/documents');
    expect(body).toMatchObject({ docType: 'PAN', attachmentId: 'att-9' });
  });

  /** Asked before it goes, because the file goes with the row. */
  it('asks before removing a paper, then removes it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await user.click(await screen.findByRole('button', { name: 'Remove Aadhaar card' }));

    expect(await screen.findByText('Remove this paper?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(del).toHaveBeenCalledOnce());
    expect(del.mock.calls[0]![0]).toBe('/staff/u-1/documents/doc-1');
  });

  it('will not mark somebody as leaving without saying which day', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await screen.findByLabelText('Aadhaar — last 4 digits');

    await user.click(screen.getByLabelText('They are leaving, or have left'));
    await user.click(screen.getByRole('button', { name: 'Save the record' }));

    expect(await screen.findByText('Which day was their last?')).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  /** Unticking is a statement of its own: he is still here, and the date goes with it. */
  it('clears a date already on file when the tick comes off', async () => {
    const user = userEvent.setup({ delay: null });
    get.mockImplementation((url: string) => {
      if (url === '/staff')
        return Promise.resolve({
          data: [{ ...STAFF[0]!, exitDate: '2026-06-30', exitReason: 'Resigned' }, STAFF[1]!],
        });
      if (url === '/staff/dashboard') return Promise.resolve({ data: DASHBOARD });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    const tick = await screen.findByLabelText('They are leaving, or have left');
    expect(tick).toBeChecked();
    expect(screen.getByLabelText('Last day')).toHaveValue('2026-06-30');

    await user.click(tick);
    await user.click(screen.getByRole('button', { name: 'Save the record' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const body = put.mock.calls[0]![1] as { exitDate?: string; exitReason?: string };
    expect(body.exitDate).toBeUndefined();
    expect(body.exitReason).toBeUndefined();
  });

  it('will not take a whole Aadhaar number', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    const aadhaar = await screen.findByLabelText('Aadhaar — last 4 digits');
    await user.clear(aadhaar);
    await user.type(aadhaar, '123456789012');

    // The box itself stops at four, which is the honest way to ask for four.
    expect(aadhaar).toHaveValue('1234');
  });

  it('refuses a probation with no length', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    const days = await screen.findByLabelText('Probation days');
    await user.clear(days);
    await user.click(screen.getByRole('button', { name: 'Save the record' }));

    expect(await screen.findByText(/An indefinite probation is not a probation/)).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  /** No edit on a row: a raise is a new revision, and both figures stay on the record. */
  it('records a raise as a revision with a reason', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Salary' }));
    await screen.findByText('Joined on probation');

    // The parts, not a total: the gross is the sum of them and is never sent, because a
    // total typed beside a breakdown is a total that can disagree with it.
    await user.clear(screen.getByLabelText('Basic pay'));
    await user.type(screen.getByLabelText('Basic pay'), '20000');
    await user.clear(screen.getByLabelText('House rent allowance'));
    await user.type(screen.getByLabelText('House rent allowance'), '4000');
    await user.type(screen.getByLabelText('Why it moved'), 'Confirmed off probation');
    await user.click(screen.getByRole('button', { name: 'Add the revision' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/staff/u-1/salary');
    expect(post.mock.calls[0]![1]).toMatchObject({
      basic: 20000,
      hra: 4000,
      reason: 'Confirmed off probation',
    });
    expect(post.mock.calls[0]![1]).not.toHaveProperty('monthlyAmount');
  });

  it('will not record a change of pay with no reason', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Salary' }));
    await screen.findByText('Joined on probation');

    await user.clear(screen.getByLabelText('Basic pay'));
    await user.type(screen.getByLabelText('Basic pay'), '24000');
    await user.click(screen.getByRole('button', { name: 'Add the revision' }));

    expect(await screen.findByText(/Say why it moved/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  /**
   * The single thing about the 2025 codes that changes what a contractor's payroll costs. An
   * office writing a low basic and large allowances to keep the fund down has to learn here,
   * before it saves, that the law counts the excess as wages anyway.
   */
  it('warns when the allowances run past half the packet', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Salary' }));
    await screen.findByText('Joined on probation');

    // Basic 8,000 of a 20,000 packet: the wage is lifted to 10,000, not left at 8,000.
    await user.clear(screen.getByLabelText('Basic pay'));
    await user.type(screen.getByLabelText('Basic pay'), '8000');
    await user.clear(screen.getByLabelText('Other allowances'));
    await user.type(screen.getByLabelText('Other allowances'), '12000');

    expect(await screen.findByText(/more than half the packet/)).toBeInTheDocument();
    expect(screen.getByText(/Writing a low basic does not/)).toBeInTheDocument();
  });

  /**
   * The letter is built into a PDF on the server, which on a site connection is several
   * seconds. A button that only goes grey reads as a button that did nothing, and the next
   * thing that happens is a second tap.
   */
  it('says the letter is being prepared while it is', async () => {
    const objectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:letter');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    let release: (value: unknown) => void = () => {};
    post.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Offer letter' }));
    await screen.findByText(/The letter states what the record already says/);

    await user.click(screen.getByRole('button', { name: 'Preview' }));

    // The word changes and both buttons lock, and Close with them: closing under a request
    // in flight loses the only place its refusal could have been shown.
    const busy = await screen.findByRole('button', { name: /Preparing the letter/ });
    expect(busy).toBeDisabled();
    expect(screen.getByRole('button', { name: /Issue and file/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();

    release({ data: new Blob(['%PDF-'], { type: 'application/pdf' }) });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Preview' })).toBeEnabled());
    objectUrl.mockRestore();
  });

  /**
   * A room and a tank of petrol are terms of the engagement. They are deliberately not
   * components of the salary: the Code on Wages keeps the value of accommodation and a sum
   * defraying special expenses out of wages, so putting either in the packet would move the
   * provident fund wage of everybody who is given a bed.
   */
  it('sends what the firm provides, and no fuel figure where there is none', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Record' }));
    await screen.findByRole('dialog');

    await user.click(screen.getByRole('checkbox', { name: 'Accommodation is provided' }));
    await user.type(
      screen.getByLabelText('Which accommodation'),
      'shared room at the site guest house',
    );
    await user.click(screen.getByRole('button', { name: 'Save the record' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(put.mock.calls[0]![1]).toMatchObject({
      accommodationProvided: true,
      accommodationNote: 'shared room at the site guest house',
      fuelProvided: false,
    });
    // Undefined rather than zero, and undefined is what JSON.stringify leaves off the wire —
    // so the server stores null. Fuel at actuals is an arrangement and no fuel at all is not
    // the statement "a fuel allowance of nothing"; a zero here would be both.
    const sent = put.mock.calls[0]![1] as { fuelMonthlyAmount?: number };
    expect(sent.fuelMonthlyAmount).toBeUndefined();
    expect(JSON.parse(JSON.stringify(sent))).not.toHaveProperty('fuelMonthlyAmount');
  });

  it('shows the register without the buttons to somebody who may only read it', async () => {
    permissions = ['staff:read'];
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Salary' })).not.toBeInTheDocument();
  });
});

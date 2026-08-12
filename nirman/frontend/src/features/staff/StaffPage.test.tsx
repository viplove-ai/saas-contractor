import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StaffPage } from './StaffPage';
import type { SalaryRevision, StaffDashboard, StaffProfile } from './types';

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

let permissions = ['staff:read', 'staff:write'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions },
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

const SALARY: SalaryRevision[] = [
  { id: 's1', monthlyAmount: 18000, effectiveFrom: '2026-02-01', reason: 'Joined on probation' },
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
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
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

    await user.clear(screen.getByLabelText('A month'));
    await user.type(screen.getByLabelText('A month'), '24000');
    await user.type(screen.getByLabelText('Why it moved'), 'Confirmed off probation');
    await user.click(screen.getByRole('button', { name: 'Add the revision' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/staff/u-1/salary');
    expect(post.mock.calls[0]![1]).toMatchObject({
      monthlyAmount: 24000,
      reason: 'Confirmed off probation',
    });
  });

  it('will not record a change of pay with no reason', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    const row = within(screen.getByRole('table')).getByText('deepak').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Salary' }));
    await screen.findByText('Joined on probation');

    await user.clear(screen.getByLabelText('A month'));
    await user.type(screen.getByLabelText('A month'), '24000');
    await user.click(screen.getByRole('button', { name: 'Add the revision' }));

    expect(await screen.findByText(/Say why it moved/)).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it('shows the register without the buttons to somebody who may only read it', async () => {
    permissions = ['staff:read'];
    renderPage();
    await screen.findAllByText('Deepak Joshi');

    expect(screen.queryByRole('button', { name: 'Record' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Salary' })).not.toBeInTheDocument();
  });
});

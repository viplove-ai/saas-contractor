import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PayrollPage } from './PayrollPage';
import type { PayrollRun, PayrollRunSummary, Payslip } from './types';

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

let permissions = ['payroll:read', 'payroll:process'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

/** The payslip from the specification: basic 15,000, other 3,000, ESI 135, EPF 1,800. */
const SLIP: Payslip = {
  id: 'slip-1',
  runId: 'run-1',
  userId: 'u-1',
  periodMonth: '2026-07-01',
  employeeName: 'Dheeraj Kumar',
  employeeNumber: '2203',
  designation: 'Site Supervisor',
  uan: '102339944397',
  esicNumber: '6114798073',
  structBasic: 15000,
  structDa: 0,
  structHra: 0,
  structConveyance: 0,
  structOther: 3000,
  structGross: 18000,
  payableDays: 26,
  paidDays: 26,
  earnedBasic: 15000,
  earnedDa: 0,
  earnedHra: 0,
  earnedConveyance: 0,
  earnedOther: 3000,
  overtimeHours: 0,
  overtimeAmount: 0,
  totalEarnings: 18000,
  statutoryWages: 15000,
  pfWages: 15000,
  pfEmployee: 1800,
  esiWages: 18000,
  esiEmployee: 135,
  professionalTax: 0,
  tds: 0,
  salaryAdvance: 0,
  otherDeduction: 0,
  totalDeductions: 1935,
  netAmount: 16065,
  pfEmployer: 550,
  epsEmployer: 1250,
  esiEmployer: 585,
  employerCost: 20385,
  finalised: false,
  version: 0,
};

const RUN: PayrollRun = {
  id: 'run-1',
  periodMonth: '2026-07-01',
  periodEnd: '2026-07-31',
  status: 'DRAFT',
  payableDays: 26,
  totals: {
    headcount: 1,
    grossEarnings: 18000,
    totalDeductions: 1935,
    netPayable: 16065,
    pfEmployee: 1800,
    pfEmployer: 550,
    epsEmployer: 1250,
    esiEmployee: 135,
    esiEmployer: 585,
    professionalTax: 0,
    tds: 0,
    employerCost: 20385,
  },
  payslips: [SLIP],
  // The point of the screen: somebody the month could not draw, with the reason.
  notDrawn: [
    {
      userId: 'u-2',
      fullName: 'Ramesh Yadav',
      reason: 'No salary has ever been recorded for this member',
    },
  ],
  version: 0,
};

const RUNS: PayrollRunSummary[] = [
  {
    id: 'run-1',
    periodMonth: '2026-07-01',
    status: 'DRAFT',
    payableDays: 26,
    headcount: 1,
    netPayable: 16065,
  },
];

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PayrollPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PayrollPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['payroll:read', 'payroll:process'];
    get.mockImplementation((url: string) => {
      if (url === '/payroll/runs') return Promise.resolve({ data: RUNS });
      if (url === '/payroll/runs/run-1') return Promise.resolve({ data: RUN });
      return Promise.resolve({ data: [] });
    });
    post.mockResolvedValue({ data: RUN });
    put.mockResolvedValue({ data: SLIP });
    del.mockResolvedValue({ data: null });
  });

  it('shows the month, its net and what employing everybody cost', async () => {
    renderPage();
    await screen.findByText('Dheeraj Kumar');

    expect(screen.getByText('Net payable')).toBeInTheDocument();
    // The employer's contributions are shown as a cost and never inside the net.
    expect(screen.getByText('Cost of employing')).toBeInTheDocument();
    const row = within(screen.getByRole('table')).getByText('Dheeraj Kumar').closest('tr')!;
    expect(within(row).getByText(/1,800/)).toBeInTheDocument();
    expect(within(row).getByText(/135/)).toBeInTheDocument();
    expect(within(row).getByText(/16,065/)).toBeInTheDocument();
  });

  /**
   * The rule that stops a payroll looking complete when it is not. Eleven slips in an office
   * of fourteen reads exactly like a finished month, and the three missing are always the
   * three nobody recorded a structure for.
   */
  it('names everybody the month could not draw, and why', async () => {
    renderPage();
    await screen.findByText('Not on this payroll');

    expect(screen.getByText('Ramesh Yadav')).toBeInTheDocument();
    expect(screen.getByText(/No salary has ever been recorded/)).toBeInTheDocument();
  });

  it('draws a month, and says which one', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Dheeraj Kumar');

    await user.click(screen.getByRole('button', { name: 'Draw this month' }));
    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(post.mock.calls[0]![0]).toBe('/payroll/runs');
    expect(post.mock.calls[0]![1]).toMatchObject({ payableDays: 26 });
  });

  it('sends only the typed figures when a slip is corrected', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Dheeraj Kumar');

    const row = within(screen.getByRole('table')).getByText('Dheeraj Kumar').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    await screen.findByRole('dialog');

    await user.clear(screen.getByLabelText('Days paid, out of 26'));
    await user.type(screen.getByLabelText('Days paid, out of 26'), '24');
    await user.clear(screen.getByLabelText('TDS (u/s 392)'));
    await user.type(screen.getByLabelText('TDS (u/s 392)'), '1500');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(put.mock.calls[0]![0]).toBe('/payroll/payslips/slip-1');
    expect(put.mock.calls[0]![1]).toMatchObject({ paidDays: 24, tds: 1500, version: 0 });
    // A cleared overtime box asks the server for the statutory double; it is not a zero.
    expect(put.mock.calls[0]![1]).toMatchObject({ overtimeAmount: undefined });
  });

  /** Reading what last month cost and deciding what this month pays are two different acts. */
  it('offers no drawing or finalising to somebody who may only read it', async () => {
    permissions = ['payroll:read'];
    renderPage();
    await screen.findByText('Dheeraj Kumar');

    expect(screen.queryByRole('button', { name: 'Draw this month' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Finalise the month' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    // The documents are still offered: reading the month includes reading its paper.
    expect(screen.getByRole('button', { name: 'Register (PDF)' })).toBeInTheDocument();
  });

  it('does not offer to finalise a month that is already final', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/payroll/runs') return Promise.resolve({ data: RUNS });
      if (url === '/payroll/runs/run-1') {
        return Promise.resolve({
          data: { ...RUN, status: 'FINALISED', finalisedAt: '2026-08-02T10:00:00Z' },
        });
      }
      return Promise.resolve({ data: [] });
    });
    renderPage();
    await screen.findByText('Finalised');

    expect(screen.queryByRole('button', { name: 'Finalise the month' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Draw again' })).not.toBeInTheDocument();
  });
});

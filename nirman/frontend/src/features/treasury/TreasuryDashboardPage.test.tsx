import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TreasuryDashboardPage } from './TreasuryDashboardPage';
import type { Security, TreasuryDashboard } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

function security(overrides: Partial<Security> = {}): Security {
  return {
    id: 'sec-1',
    projectId: 'p1',
    projectCode: 'KSN01',
    projectName: 'Kausani Guest House',
    securityType: 'PERFORMANCE_GUARANTEE',
    instrument: 'FDR',
    status: 'LODGED',
    amount: 500000,
    heldAmount: 500000,
    referenceNo: 'FDR/9912',
    bankName: 'SBI',
    expectedReleaseOn: '2026-01-01',
    daysToRelease: -20,
    needsAttention: true,
    freeToReuse: false,
    version: 1,
    ...overrides,
  };
}

/**
 * A crore of estimate bid thirty per cent below: five lakh of guarantee, ten lakh of additional
 * guarantee, and ninety-five thousand the department has withheld from bills so far. The last
 * one is the figure the whole screen is built to keep separate.
 */
function dashboard(overrides: Partial<TreasuryDashboard> = {}): TreasuryDashboard {
  return {
    asOf: '2026-08-18',
    totalBlocked: 1595000,
    lodgedFromOwnFunds: 1500000,
    retainedFromBills: 95000,
    awaitingLodgement: 250000,
    awaitingLodgementCount: 1,
    releasableNow: 500000,
    releasableNowCount: 1,
    releasingIn30Days: 0,
    releasingIn90Days: 0,
    releasingIn365Days: 1000000,
    freeToReuse: 240000,
    freeToReuseCount: 1,
    releasedThisFinancialYear: 240000,
    redeployedThisFinancialYear: 0,
    forfeitedToDate: 0,
    byType: [
      { securityType: 'EMD', held: 0, count: 0, awaiting: 250000 },
      { securityType: 'PERFORMANCE_GUARANTEE', held: 500000, count: 1, awaiting: 0 },
      { securityType: 'ADDITIONAL_PG', held: 1000000, count: 1, awaiting: 0 },
      { securityType: 'SECURITY_DEPOSIT', held: 95000, count: 1, awaiting: 0 },
    ],
    byBank: [{ bankName: 'SBI', held: 1500000, count: 2 }],
    releaseCalendar: [{ month: '2026-08', lodged: 500000, retained: 0, count: 1 }],
    projects: [
      {
        projectId: 'p1',
        projectCode: 'KSN01',
        projectName: 'Kausani Guest House',
        status: 'ACTIVE',
        contractValue: 7000000,
        emdHeld: 0,
        performanceGuaranteeHeld: 500000,
        additionalPgHeld: 1000000,
        securityDepositHeld: 95000,
        totalHeld: 1595000,
        awaiting: 250000,
        releasableNow: 500000,
        nextReleaseOn: '2026-01-01',
        nextReleaseAmount: 500000,
        attentionCount: 1,
      },
    ],
    needsAttention: [security()],
    reusable: [
      security({
        id: 'sec-2',
        securityType: 'EMD',
        status: 'RELEASED',
        amount: 240000,
        heldAmount: 0,
        releasedOn: '2026-05-02',
        needsAttention: false,
        freeToReuse: true,
      }),
    ],
    caveat: 'Amounts are what the register says was lodged.',
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <TreasuryDashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TreasuryDashboardPage', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: dashboard() });
  });

  it('never prints the blocked total without its two halves beside it', async () => {
    renderPage();

    /*
      The sum is a real question — how much of the contract's value is not with us. But a
      reader who cannot see the split treats it as cash he can go and fetch, and a fifteenth
      of it was never his to fetch.

      getAllBy throughout this file rather than getBy: RecordTable renders the table and the
      card stack into the same DOM and lets the breakpoint hide one, so every register value
      is present twice and a strict getBy would be asserting on the layout instead of the
      figure.
    */
    expect(await screen.findAllByText('₹15,95,000.00')).not.toHaveLength(0);
    expect(screen.getByText('Deposits we placed')).toBeInTheDocument();
    expect(screen.getByText('Withheld from our bills')).toBeInTheDocument();
    expect(screen.getAllByText('₹15,00,000.00')).not.toHaveLength(0);
    expect(screen.getAllByText('₹95,000.00')).not.toHaveLength(0);
  });

  it('says what is still to be found, which is not the same as what is held', async () => {
    renderPage();

    const tile = (await screen.findByText('Still to lodge')).closest('div');
    expect(tile).not.toBeNull();
    expect(within(tile as HTMLElement).getByText('₹2,50,000.00')).toBeInTheDocument();
    expect(screen.getByText('1 recorded and not yet placed')).toBeInTheDocument();
  });

  it('separates an overdue release from a deposit maturing too early', async () => {
    get.mockResolvedValue({
      data: dashboard({
        needsAttention: [
          security({ id: 'late', daysToRelease: -20 }),
          // Not due for release for years; the fixed deposit behind it matured last year.
          security({ id: 'stale', daysToRelease: 900, expectedReleaseOn: '2029-01-01' }),
        ],
      }),
    });
    renderPage();

    expect(await screen.findAllByText('Release overdue')).not.toHaveLength(0);
    expect(screen.getAllByText('Matures before release — renew')).not.toHaveLength(0);
  });

  it('lists what is free to bid with, which is the point of tracking earnest money at all', async () => {
    renderPage();

    expect(await screen.findByText('Free to fund the next tender')).toBeInTheDocument();
    expect(screen.getAllByText('₹2,40,000.00')).not.toHaveLength(0);
  });

  it('shows the four kinds apart, so a guarantee is never read as earnest money', async () => {
    renderPage();

    expect(await screen.findByText('Earnest money')).toBeInTheDocument();
    expect(screen.getByText('Performance guarantee')).toBeInTheDocument();
    expect(screen.getByText('Additional guarantee')).toBeInTheDocument();
    expect(screen.getByText('Security deposit')).toBeInTheDocument();
  });

  it('drops the attention panel entirely when there is nothing to chase', async () => {
    get.mockResolvedValue({ data: dashboard({ needsAttention: [], reusable: [] }) });
    renderPage();

    expect(await screen.findByText('Treasury')).toBeInTheDocument();
    expect(screen.queryByText('Needs a telephone call')).not.toBeInTheDocument();
    expect(screen.queryByText('Free to fund the next tender')).not.toBeInTheDocument();
  });
});

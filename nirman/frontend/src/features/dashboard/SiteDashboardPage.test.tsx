import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SiteDashboardPage } from './SiteDashboardPage';
import type { SiteDashboard } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

function dashboard(overrides: Partial<SiteDashboard> = {}): SiteDashboard {
  return {
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Main Block',
    projectId: 'p1',
    projectName: 'Kausani Guest House Extension',
    from: '2025-06-01',
    to: '2025-06-30',
    labour: {
      manDays: 6,
      regularHours: 42,
      overtimeHours: 2,
      cost: 3650,
      verifiedCost: 0,
      unverifiedCost: 3650,
      pendingVerification: 6,
      daysWithAttendance: 2,
      daysWithoutAttendance: 20,
    },
    material: {
      openingValue: 0,
      received: 41000,
      consumed: 3200,
      inventoryValue: 37800,
      residual: 0,
      reconciles: true,
      issued: 3200,
      wasted: 0,
      purchased: 40000,
      purchasedNotReceived: -1000,
      note: 'Opening plus received less consumed lands on the stock value the balances hold, so the three figures are the whole picture.',
    },
    cash: {
      totalBooked: 41800,
      costIncurred: 1800,
      materialPurchases: 40000,
      labourDisbursements: 0,
      paid: 0,
      payable: 40000,
      awaitingApproval: 1,
    },
    progress: {
      contractValue: 5400000,
      valueOfWorkDone: 612000,
      percentComplete: 11.33,
      itemsTotal: 8,
      itemsCompleted: 1,
      itemsInProgress: 3,
      itemsOverClaimed: 0,
    },
    dpr: {
      reportsInRange: 2,
      verified: 1,
      awaitingVerification: 0,
      draft: 1,
      daysWithoutReport: ['2025-06-11', '2025-06-12'],
    },
    trend: [{ date: '2025-06-10', labourCost: 1875, materialConsumed: 3200, otherCost: 1800 }],
    topWorkItems: [
      {
        boqItemId: 'boq-brick',
        itemNumber: 'B-003',
        description: 'Brickwork 1:4 in superstructure',
        contractQuantity: 50,
        completedQuantity: 18,
        percentComplete: 36,
        overClaimedQuantity: 0,
        contractAmount: 340000,
        status: 'IN_PROGRESS',
      },
    ],
    caveat: 'Total booked is what left the books and is not a cost figure.',
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard/site/site-a']}>
        <Routes>
          <Route path="/dashboard/site/:siteId" element={<SiteDashboardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(data: SiteDashboard = dashboard()) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') {
      return Promise.resolve({
        data: [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }],
      });
    }
    if (url === '/dashboard/site/site-a') return Promise.resolve({ data });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('SiteDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGets();
  });

  /** The same three material figures the company dashboard uses, and the same equation. */
  it('reports material with the three figures that add up', async () => {
    renderPage();

    expect(await screen.findByText('In store now')).toBeInTheDocument();
    expect(screen.getByText('Received').nextSibling).toHaveTextContent('41,000');
    expect(screen.getByText('Consumed').nextSibling).toHaveTextContent('3,200');
  });

  /**
   * The wage is frozen at verification, so the unsigned part can still move. A single blended
   * figure would move with it silently.
   */
  it('flags the part of the wage bill nobody has signed for', async () => {
    renderPage();

    expect(await screen.findByText(/is not verified yet \(6 row\(s\) waiting\)/)).toBeInTheDocument();
  });

  /** Four cash figures, kept apart the way Phase 5 refuses to merge them. */
  it('keeps cost incurred, material purchases and wage payments apart', async () => {
    renderPage();

    expect(await screen.findByText('Cost incurred')).toBeInTheDocument();
    expect(screen.getByText('Material purchases').nextSibling).toHaveTextContent('40,000');
    expect(screen.getByText('Wage payments')).toBeInTheDocument();
    expect(screen.getByText('Total booked').nextSibling).toHaveTextContent('41,800');
  });

  /** Progress by value, and the sentence that says why it is not by line count. */
  it('reports contract progress by value', async () => {
    renderPage();

    expect(await screen.findByText('11.3%')).toBeInTheDocument();
    expect(screen.getByText(/By value, not by line count/)).toBeInTheDocument();
  });

  /**
   * The dates rather than the count. A gap in the diary makes every other figure on the page
   * an understatement, and the dates are what somebody can act on.
   */
  it('names the days with no report rather than only counting them', async () => {
    renderPage();

    expect(await screen.findByText(/No report on 2025-06-11, 2025-06-12/)).toBeInTheDocument();
  });

  /** Claiming past the contract quantity is a warning, not a silently capped bar. */
  it('warns when a work item is claimed beyond its contract quantity', async () => {
    const base = dashboard();
    mockGets({
      ...base,
      progress: { ...base.progress, itemsOverClaimed: 2 },
    });
    renderPage();

    expect(
      await screen.findByText(/2 line\(s\) are claimed beyond their contract quantity/),
    ).toBeInTheDocument();
  });
});

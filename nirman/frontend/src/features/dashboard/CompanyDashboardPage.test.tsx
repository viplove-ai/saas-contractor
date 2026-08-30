import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CompanyDashboardPage } from './CompanyDashboardPage';
import type { CompanyDashboard, MaterialPosition } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

/**
 * Opening 1,00,000 + received 4,10,000 − consumed 1,60,000 = 3,50,000 standing in the stores.
 * Purchased is 4,50,000, which is deliberately *not* one of those terms: it comes from the
 * bills, it is ahead of the deliveries by 40,000, and adding it to consumption would count the
 * same cement twice.
 */
function material(overrides: Partial<MaterialPosition> = {}): MaterialPosition {
  return {
    openingValue: 100000,
    received: 410000,
    consumed: 160000,
    inventoryValue: 350000,
    residual: 0,
    reconciles: true,
    issued: 148000,
    wasted: 12000,
    purchased: 450000,
    purchasedNotReceived: 40000,
    note: 'Opening plus received less consumed lands on the stock value the balances hold, so the three figures are the whole picture.',
    ...overrides,
  };
}

function dashboard(overrides: Partial<CompanyDashboard> = {}): CompanyDashboard {
  return {
    from: '2025-06-01',
    to: '2025-06-30',
    activeProjects: 1,
    activeSites: 2,
    quotedValue: 16465000,
    unpricedProjects: 0,
    budgetAmount: 16000000,
    labourCost: 499528,
    materialConsumed: 160000,
    otherCost: 88000,
    costIncurred: 747528,
    totalBooked: 1197528,
    payable: 210000,
    material: material(),
    projects: [
      {
        projectId: 'p1',
        projectCode: 'KSN01',
        projectName: 'Kausani Guest House Extension',
        status: 'ACTIVE',
        quotedCost: 16465000,
        budgetAmount: 16000000,
        costIncurred: 747528,
        percentBudgetUsed: 4.67,
        contractedWorkDone: 2100000,
        percentWorkDone: 11.35,
        siteCount: 2,
      },
    ],
    trend: [
      { date: '2025-06-09', labourCost: 1775, materialConsumed: 0, otherCost: 0 },
      { date: '2025-06-10', labourCost: 1875, materialConsumed: 3200, otherCost: 1800 },
    ],
    caveat:
      'Cost incurred is labour, plus material consumed at the moving average it left store at, plus expenses that are neither a material purchase nor a wage payment. Total booked is what left the books and is not a cost figure.',
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <CompanyDashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CompanyDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/dashboard/admin') return Promise.resolve({ data: dashboard() });
      if (url === '/sites') return Promise.resolve({ data: [] });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
  });

  /**
   * The Phase 6 exit criterion, on the screen: material appears as three separate figures and
   * the equation that ties them to what the stores actually hold.
   */
  it('shows material received, consumed and in store as three separate figures', async () => {
    renderPage();

    expect(await screen.findByText('Opening')).toBeInTheDocument();
    expect(screen.getByText('Received')).toBeInTheDocument();
    expect(screen.getByText('Consumed')).toBeInTheDocument();
    expect(screen.getByText('In store now')).toBeInTheDocument();

    expect(screen.getByText('Received').nextSibling).toHaveTextContent('4,10,000');
    expect(screen.getByText('Consumed').nextSibling).toHaveTextContent('1,60,000');
    expect(screen.getByText('In store now').nextSibling).toHaveTextContent('3,50,000');
  });

  /**
   * Purchased is from the bills and is not a term of that sum. Showing it apart is what stops
   * a reader adding it to consumption and overstating the project by the value of its own
   * stockyard.
   */
  it('keeps material purchased apart from the three that add up', async () => {
    renderPage();

    expect(await screen.findByText('Purchased (from bills)')).toBeInTheDocument();
    expect(screen.getByText('Purchased (from bills)').nextSibling).toHaveTextContent('4,50,000');
    expect(screen.getByText('Billed, not yet received').nextSibling).toHaveTextContent('40,000');
  });

  /** Cost and cash are two headings, not one number, and the caveat says which is which. */
  it('shows cost incurred apart from what left the books', async () => {
    renderPage();

    expect(await screen.findByText('What the period cost')).toBeInTheDocument();
    expect(screen.getByText('What left the books')).toBeInTheDocument();
    expect(screen.getByText('Cost incurred').nextSibling).toHaveTextContent('7,47,528');
    expect(screen.getByText('Total booked').nextSibling).toHaveTextContent('11,97,528');
    expect(screen.getByText(/is not a cost figure/)).toBeInTheDocument();
  });

  /** A reconciliation you only see when it fails is one nobody believes when it passes. */
  it('states the reconciliation even when it holds', async () => {
    renderPage();

    expect(
      await screen.findByText(/three figures are the whole picture/),
    ).toBeInTheDocument();
  });

  /** A drifted ledger is worth seeing rather than papering over. */
  it('shows the residual when the ledger and its cache disagree', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/dashboard/admin') {
        return Promise.resolve({
          data: dashboard({
            material: material({
              residual: 2500,
              reconciles: false,
              note: 'Opening plus received less consumed does not land on the stock value the balances hold.',
            }),
          }),
        });
      }
      return Promise.resolve({ data: [] });
    });
    renderPage();

    expect(await screen.findByText('Unexplained')).toBeInTheDocument();
    expect(screen.getByText(/does not land on the stock value/)).toBeInTheDocument();
  });

  /** No budget is not nought per cent used — it is a question nobody can answer. */
  /**
   * The headline is what the firm may invoice, not what the department estimated. A tile built
   * on the estimate tells a contractor who bid below it that his order book is larger than
   * anything he can send a bill for.
   */
  it('heads the page with the quoted value rather than the contract value', async () => {
    renderPage();

    // Three times: the tile, the table column, and the same column on the card layout that
    // RecordTable renders alongside it for a hand.
    expect(await screen.findAllByText('Quoted value')).toHaveLength(3);
    expect(screen.queryByText('Contract value')).not.toBeInTheDocument();
    expect(screen.queryByText('Contract')).not.toBeInTheDocument();
  });

  it('says how many projects the quoted total leaves out', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/dashboard/admin') {
        return Promise.resolve({ data: { ...dashboard(), unpricedProjects: 3 } });
      }
      return Promise.resolve({ data: [] });
    });
    renderPage();

    expect(
      await screen.findByText('3 projects carry no quote and are not in this total'),
    ).toBeInTheDocument();
  });

  /** An em dash, never the contract value: two figures in one column total to nothing. */
  it('leaves an unquoted project blank in the quoted column', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/dashboard/admin') {
        const base = dashboard();
        const project = { ...base.projects[0]!, quotedCost: undefined };
        return Promise.resolve({ data: { ...base, projects: [project] } });
      }
      return Promise.resolve({ data: [] });
    });
    renderPage();

    const table = within(await screen.findByRole('table', { name: 'Projects over the period' }));
    expect(table.getByText('Quoted value')).toBeInTheDocument();
    expect(table.getAllByText('\u2014').length).toBeGreaterThan(0);
  });

  it('says a project has no budget rather than showing it as zero per cent used', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/dashboard/admin') {
        const base = dashboard();
        const project = { ...base.projects[0]!, percentBudgetUsed: undefined };
        return Promise.resolve({ data: { ...base, projects: [project] } });
      }
      return Promise.resolve({ data: [] });
    });
    renderPage();

    // Both layouts carry the figure; the desk one is enough to prove it is not "0.0%".
    await screen.findAllByText('No budget set');
    const table = within(screen.getByRole('table', { name: 'Projects over the period' }));
    expect(table.getByText('No budget set')).toBeInTheDocument();
  });
});

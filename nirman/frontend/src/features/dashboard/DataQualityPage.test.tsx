import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DataQualityPage } from './DataQualityPage';
import type { DataQualityDashboard } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

function quality(overrides: Partial<DataQualityDashboard> = {}): DataQualityDashboard {
  return {
    from: '2025-06-01',
    to: '2025-06-30',
    scopeName: 'Kausani Main Block',
    actCount: 1,
    watchCount: 1,
    findings: [
      {
        code: 'issues.without-boq-item',
        title: 'Material issued against no work item',
        count: 4,
        severity: 'WATCH',
        detail: 'Four issues this month are charged to no contract line.',
        whatToDo: 'Open each issue and charge it to the line the material went into.',
        examples: ['ISS-2025-0012', 'ISS-2025-0014'],
      },
      {
        code: 'attendance.unverified',
        title: 'Attendance not verified',
        count: 6,
        severity: 'ACT',
        detail: 'Six rows are submitted and unsigned, so their wages are still provisional.',
        whatToDo: 'Verify the roster for 9 and 10 June.',
        examples: ['2025-06-09', '2025-06-10'],
      },
    ],
    caveat: 'Findings cover the period and scope selected above.',
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DataQualityPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockGets(data: DataQualityDashboard = quality()) {
  get.mockImplementation((url: string) => {
    if (url === '/sites') return Promise.resolve({ data: [] });
    if (url === '/dashboard/data-quality') return Promise.resolve({ data });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('DataQualityPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGets();
  });

  /**
   * A dashboard that only counts problems gets ignored by the second week. Every finding
   * carries what to do about it and the evidence behind the count.
   */
  it('gives every finding an action and its evidence', async () => {
    renderPage();

    expect(await screen.findByText('Attendance not verified')).toBeInTheDocument();
    expect(screen.getByText('Verify the roster for 9 and 10 June.')).toBeInTheDocument();
    expect(screen.getByText('2025-06-09')).toBeInTheDocument();
    expect(screen.getByText('2025-06-10')).toBeInTheDocument();
  });

  /** The list is a work queue, so what has to be acted on comes before what is watched. */
  it('puts what to act on above what to watch', async () => {
    renderPage();

    await screen.findByText('Attendance not verified');
    const titles = screen
      .getAllByText(/Attendance not verified|Material issued against no work item/)
      .map((node) => node.textContent);

    expect(titles[0]).toBe('Attendance not verified');
  });

  it('summarises how much needs acting on', async () => {
    renderPage();

    expect(await screen.findByText('1 to act on')).toBeInTheDocument();
    expect(screen.getByText('1 to watch')).toBeInTheDocument();
  });

  /** Nothing wrong is a result worth stating plainly, not an empty page. */
  it('says so plainly when nothing is missing', async () => {
    mockGets(quality({ findings: [], actCount: 0, watchCount: 0 }));
    renderPage();

    expect(await screen.findByText(/Nothing missing in this window/)).toBeInTheDocument();
  });
});

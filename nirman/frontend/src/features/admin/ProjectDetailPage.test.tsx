import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectDetailPage } from './ProjectDetailPage';
import type { AdminProject, BoqItem, NitDocument, UnitOption } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

const PROJECT: AdminProject = {
  id: 'p1',
  code: 'ALM30',
  name: 'Tile work in Type-2 and Type-3 quarters',
  clientDepartment: 'CPWD Almora',
  nitNumber: '30/EE/ACD/CPWD/Almora/2026-27',
  contractValue: 4226546,
  status: 'ACTIVE',
  version: 1,
};

const UNITS: UnitOption[] = [
  { id: 'u-sqm', code: 'SQM', name: 'Square Metre', decimalPlaces: 2, active: true },
  { id: 'u-lot', code: 'LOT', name: 'Lot', decimalPlaces: 0, active: true },
];

const BOQ: BoqItem[] = [
  {
    id: 'b1',
    projectId: 'p1',
    siteId: null,
    itemNumber: 'C/1.1.1',
    description: 'Vitrified tiles, size 600x600 mm',
    unitId: 'u-sqm',
    contractQuantity: 155,
    contractRate: 2377.15,
    contractAmount: 368458.25,
    completedQuantity: 0,
    status: 'NOT_STARTED',
    workPart: 'Civil Works',
    category: 'Flooring & Finishes',
    synthetic: false,
    sortOrder: 0,
    version: 0,
  },
  {
    id: 'b2',
    projectId: 'p1',
    siteId: null,
    itemNumber: 'C/2.1',
    description: '12 mm cement plaster',
    unitId: 'u-sqm',
    contractQuantity: 200,
    contractRate: 300,
    contractAmount: 60000,
    completedQuantity: 0,
    status: 'NOT_STARTED',
    workPart: 'Civil Works',
    category: 'Flooring & Finishes',
    synthetic: false,
    sortOrder: 1,
    version: 0,
  },
  {
    id: 'b3',
    projectId: 'p1',
    siteId: null,
    itemNumber: 'UNALLOCATED',
    description: 'Stated BOQ total not represented by extracted priced rows',
    unitId: 'u-lot',
    contractQuantity: 1,
    contractRate: 12000,
    contractAmount: 12000,
    completedQuantity: 0,
    status: 'NOT_STARTED',
    workPart: null,
    category: 'Unallocated BOQ Balance',
    synthetic: true,
    sortOrder: 2,
    version: 0,
  },
];

const NIT: NitDocument = {
  id: 'n1',
  projectId: 'p1',
  attachmentId: 'a1',
  fileName: 'almora-30-tile-work.pdf',
  pageCount: 134,
  parserVersion: '1.0.0',
  fields: {
    nitNo: '30/EE/ACD/CPWD/Almora/2026-27',
    workName: 'Tile work in Type-2 and Type-3 quarters',
    estimatedCost: 4226546,
    civilEstimatedCost: null,
    electricalEstimatedCost: null,
    emdAmount: 84531,
    completionPeriod: '6 (Six) months',
    submissionClosing: '2026-07-23T15:00:00',
    bidOpening: '2026-07-23T15:30:00',
    division: 'Almora',
    location: 'VPKAS, Hawalbagh and Almora (UK)',
    bidType: 'Percentage Rate',
    contractorEligibility: null,
    similarWorkCriteria: null,
    performanceGuaranteePercent: 5,
    securityDepositPercent: 2.5,
    civilDsrYear: 2023,
    civilCostIndexPercent: 29,
    electricalDsrYear: null,
    electricalCostIndexPercent: null,
  },
  boqTotal: 4226546,
  extractedItemCount: 3,
  warnings: ['2 item numbers were prefixed by work part so each line is unique within the project.'],
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/p1']}>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
          <Route path="/projects" element={<div>All projects table</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProjectDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/projects/p1') return Promise.resolve({ data: PROJECT });
      if (url === '/boq-items') return Promise.resolve({ data: BOQ });
      if (url === '/units') return Promise.resolve({ data: UNITS });
      if (url === '/nit-imports/projects/p1') return Promise.resolve({ data: NIT });
      return Promise.reject(new Error(`unexpected ${url}`));
    });
  });

  it('shows the contract, the tender it came from, and the whole schedule', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'ALM30' })).toBeInTheDocument();
    expect(screen.getByText('CPWD Almora')).toBeInTheDocument();

    // Captured from the NIT, not from the project record.
    expect(screen.getByText('Tender notice')).toBeInTheDocument();
    expect(screen.getByText('almora-30-tile-work.pdf · 134 pages')).toBeInTheDocument();
    expect(screen.getByText('6 (Six) months')).toBeInTheDocument();
    expect(screen.getByText('DSR 2023, cost index 29%')).toBeInTheDocument();
    expect(screen.getByText('23/07/2026, 15:30')).toBeInTheDocument();

    expect(await screen.findByText('C/1.1.1')).toBeInTheDocument();
    expect(screen.getByText('Vitrified tiles, size 600x600 mm')).toBeInTheDocument();
    // The unit is resolved from master data rather than shown as a uuid.
    expect(screen.getByText('155 SQM')).toBeInTheDocument();
  });

  it('marks a reconciliation line as one', async () => {
    renderPage();
    expect(await screen.findByText('UNALLOCATED')).toBeInTheDocument();
    expect(screen.getByText('reconciliation')).toBeInTheDocument();
    expect(screen.getByText(/₹12,000.00 unallocated/)).toBeInTheDocument();
  });

  it('filters the schedule without refetching it', async () => {
    renderPage();
    await screen.findByText('C/1.1.1');

    await userEvent.type(screen.getByLabelText('Find an item'), 'plaster');

    await waitFor(() => expect(screen.queryByText('C/1.1.1')).not.toBeInTheDocument());
    expect(screen.getByText('C/2.1')).toBeInTheDocument();
    expect(screen.getByText(/1 of 3 lines/)).toBeInTheDocument();
    expect(get.mock.calls.filter(([url]) => url === '/boq-items')).toHaveLength(1);
  });

  it('goes back to the projects table', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'ALM30' });

    await userEvent.click(screen.getByRole('link', { name: 'Back to all projects' }));

    expect(await screen.findByText('All projects table')).toBeInTheDocument();
  });

  it('omits the tender section for a project that was typed in by hand', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/projects/p1') return Promise.resolve({ data: PROJECT });
      if (url === '/boq-items') return Promise.resolve({ data: [] });
      if (url === '/units') return Promise.resolve({ data: UNITS });
      // No NIT was ever imported for this project.
      return Promise.reject(new Error('Not Found'));
    });
    renderPage();

    await screen.findByRole('heading', { name: 'ALM30' });
    await waitFor(() => expect(screen.queryByText('Tender notice')).not.toBeInTheDocument());
    expect(screen.getByText('No work items recorded for this project yet.')).toBeInTheDocument();
  });
});

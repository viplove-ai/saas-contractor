import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BillingProjectsPage } from './BillingProjectsPage';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BillingProjectsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/*
  This file exists because the screen once shipped broken: it read a paginated envelope as an
  array and died on `.map is not a function` before drawing anything. The types said otherwise
  and the compiler believed them, because a response body is the one thing it cannot check.

  So the fixture is whatever the server actually sends, and the assertions are about what the
  card is for: telling somebody whether anything of theirs is waiting here, and where this
  tender's billing got to.
*/

const summaries = [
  {
    id: 'p1',
    code: 'KSN01',
    name: 'Kausani Guest House Extension',
    billingOnly: false,
    agreementNo: 'AGR/2024/117',
    contractorName: 'M/s Unique Associates',
    boqItemCount: 42,
    unbilledSheets: 3,
    draftSheets: 1,
    billCount: 2,
    lastBillTitle: '2nd RA Bill',
    lastBillStatus: 'PASSED',
    grossBilledToDate: '4600200.00',
    agreementRecorded: true,
  },
  {
    id: 'p2',
    code: 'ITBP7',
    name: 'ITBP Watch Towers',
    billingOnly: true,
    agreementNo: null,
    contractorName: null,
    boqItemCount: 74,
    unbilledSheets: 0,
    draftSheets: 0,
    billCount: 0,
    lastBillTitle: null,
    lastBillStatus: null,
    grossBilledToDate: null,
    agreementRecorded: false,
  },
];

describe('the billing project cards', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: summaries });
  });

  it('asks the billing endpoint, not the plain project list', async () => {
    renderPage();
    await waitFor(() => expect(get).toHaveBeenCalledWith('/billing/projects'));
  });

  it('shows both kinds of tender in one list', async () => {
    renderPage();
    await waitFor(() =>
      expect(screen.getByText('Kausani Guest House Extension')).toBeInTheDocument(),
    );
    expect(screen.getByText('ITBP Watch Towers')).toBeInTheDocument();
  });

  it('marks the tender imported only to bill', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('ITBP Watch Towers')).toBeInTheDocument());

    // Two: the filter chip, which is a button, and the marker on the one billing-only card,
    // which is not. Asserting the count catches the marker disappearing without the filter
    // masking it.
    const labels = screen.getAllByText('Billing only');
    expect(labels).toHaveLength(2);
    expect(labels.filter((label) => label.closest('[role="button"]') === null)).toHaveLength(1);
  });

  /** The question somebody arrives with: is there anything of mine waiting here. */
  it('leads with what is waiting, and totals it across tenders', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('3 sheets waiting')).toBeInTheDocument());
    expect(screen.getByText('3 sheets')).toBeInTheDocument();
    expect(screen.getByText('1 unsigned')).toBeInTheDocument();
  });

  it('says where the bill series got to, and says so when it has not started', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText(/2nd RA Bill · passed/)).toBeInTheDocument());
    expect(screen.getByText('No bills yet')).toBeInTheDocument();
  });

  /** Filtering is local — mode is a flag on a list the page already holds. */
  it('narrows to billing-only tenders without another request', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByText('Kausani Guest House Extension')).toBeInTheDocument());

    const callsBefore = get.mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Billing only' }));

    expect(screen.queryByText('Kausani Guest House Extension')).not.toBeInTheDocument();
    expect(screen.getByText('ITBP Watch Towers')).toBeInTheDocument();
    expect(get.mock.calls.length).toBe(callsBefore);
  });

  it('says so plainly when there is nothing to bill against', async () => {
    get.mockResolvedValue({ data: [] });
    renderPage();
    await waitFor(() => expect(screen.getByText(/No tenders yet/i)).toBeInTheDocument());
  });
});

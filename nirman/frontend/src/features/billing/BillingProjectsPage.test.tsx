import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
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
  This file exists because the screen shipped broken.

  `/projects` is paginated and returns a PageResponse — an object with a `content` array —
  and the hook treated it as an array, so the page died on `.map is not a function` before it
  drew anything. A test that renders the component against the shape the server actually sends
  is the only kind that catches that; the types said `Project[]` and TypeScript believed them,
  because a response body is not something the compiler can check.

  So the fixture below is deliberately a whole PageResponse, envelope and all, rather than the
  array the component wants.
*/

describe('the billing project picker', () => {
  beforeEach(() => {
    get.mockReset();
  });

  const page = {
    data: {
      content: [
        { id: 'p1', code: 'KSN01', name: 'Kausani Guest House Extension', mode: 'FULL' },
        { id: 'p2', code: 'ITBP7', name: 'ITBP Watch Towers', mode: 'BILLING_ONLY' },
      ],
      page: 0,
      size: 100,
      totalElements: 2,
      totalPages: 1,
      first: true,
      last: true,
    },
  };

  it('reads the projects out of the paginated envelope', async () => {
    get.mockResolvedValue(page);
    renderPage();

    await waitFor(() =>
      expect(screen.getByText('Kausani Guest House Extension')).toBeInTheDocument(),
    );
    expect(screen.getByText('ITBP Watch Towers')).toBeInTheDocument();
    expect(screen.getByText('KSN01')).toBeInTheDocument();
  });

  /** A picker that silently showed the first 25 of 30 tenders would be worse than an error. */
  it('asks for a page big enough to be a picker rather than a register', async () => {
    get.mockResolvedValue(page);
    renderPage();

    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(get).toHaveBeenCalledWith('/projects', { params: { size: 100 } });
  });

  it('marks a tender imported only to bill', async () => {
    get.mockResolvedValue(page);
    renderPage();

    await waitFor(() => expect(screen.getByText('Billing only')).toBeInTheDocument());
  });

  it('says so plainly when there is nothing to bill against', async () => {
    get.mockResolvedValue({
      data: { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0, first: true, last: true },
    });
    renderPage();

    await waitFor(() => expect(screen.getByText(/No projects yet/i)).toBeInTheDocument());
  });
});

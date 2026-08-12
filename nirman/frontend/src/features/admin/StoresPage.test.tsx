import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StoresPage } from './StoresPage';
import type { AdminSite, AdminStore } from './types';

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

const PERMISSIONS = ['site:read', 'site:write', 'site:delete'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: PERMISSIONS },
    hasPermission: (code: string) => PERMISSIONS.includes(code),
  }),
}));

const SITES: AdminSite[] = [
  {
    id: 'site-a',
    projectId: 'p1',
    code: 'KSN-A',
    name: 'Kausani Main Block',
    status: 'ACTIVE',
    standardShiftHours: 8,
    monthlyWageDays: 26,
    usesOutsourcedLabour: false,
    siteEngineerIds: [],
    supervisorIds: [],
    version: 0,
  },
];

/** What a site is given at creation, plus the one an admin added afterwards. */
const STORES: AdminStore[] = [
  {
    id: 'store-default',
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Main Block',
    code: 'site-KSN-A',
    name: 'site-Kausani Main Block',
    defaultStore: true,
    active: true,
    version: 0,
  },
  {
    id: 'store-steel',
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Main Block',
    code: 'KSN-A-STEEL',
    name: 'Steel yard',
    location: 'north gate',
    defaultStore: false,
    active: false,
    version: 3,
  },
];

function renderPage(route = '/stores') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <StoresPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Table and cards are both in the DOM under jsdom, so assertions are scoped to the table. */
const table = () => within(screen.getByRole('table'));

describe('StoresPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/stores') return Promise.resolve({ data: STORES });
      if (url === '/sites') return Promise.resolve({ data: SITES });
      return Promise.resolve({ data: [] });
    });
    del.mockResolvedValue({ data: undefined });
  });

  it('names the site each store belongs to, and which one the screens open on', async () => {
    renderPage();

    await waitFor(() => expect(table().getByText('site-KSN-A')).toBeInTheDocument());
    const row = table().getByText('site-KSN-A').closest('tr');
    expect(row).not.toBeNull();
    // Without the site beside it, "site-KSN-A" tells an administrator nothing about which
    // gate the material is behind.
    expect(within(row!).getByText('KSN-A')).toBeInTheDocument();
    expect(within(row!).getByText('Default')).toBeInTheDocument();

    // A store taken out of use says so rather than looking like any other row.
    const steel = table().getByText('KSN-A-STEEL').closest('tr');
    expect(within(steel!).getByText('Not in use')).toBeInTheDocument();
  });

  it('narrows to one site through the URL, so the narrowed register is a link', async () => {
    renderPage('/stores?siteId=site-a');

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/stores', { params: { siteId: 'site-a' } }),
    );
  });

  it('deletes a store and shows the server’s refusal in place when it will not go', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(table().getByText('KSN-A-STEEL')).toBeInTheDocument());

    const steel = table().getByText('KSN-A-STEEL').closest('tr');
    await user.click(within(steel!).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(del).toHaveBeenCalledWith('/stores/store-steel'));

    // Refusing is the ordinary answer for a store the ledger has touched, so the reason has
    // to land on the screen rather than in a dialog that has already closed.
    del.mockRejectedValueOnce({
      response: { data: { detail: 'This store has 5 stock ledger entries recorded against it.' } },
      isAxiosError: true,
    });
    const first = table().getByText('site-KSN-A').closest('tr');
    await user.click(within(first!).getByRole('button', { name: 'Delete' }));
    await waitFor(() =>
      expect(screen.getByText(/5 stock ledger entries/)).toBeInTheDocument(),
    );
  });
});

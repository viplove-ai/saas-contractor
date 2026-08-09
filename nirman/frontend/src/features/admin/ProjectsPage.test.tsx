import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectsPage } from './ProjectsPage';
import type { AdminProject, AdminSite, AdminUser, PageResponse } from './types';

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

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: ['project:read', 'project:write'] },
    hasPermission: (code: string) => ['project:read', 'project:write'].includes(code),
  }),
}));

const PROJECTS: AdminProject[] = [
  {
    id: 'p1',
    code: 'KSN01',
    name: 'Kausani Guest House Extension',
    clientDepartment: 'CPWD',
    contractValue: 18500000,
    status: 'ACTIVE',
    version: 1,
  },
  {
    id: 'p2',
    code: 'BAG02',
    name: 'Bageshwar Road Works',
    status: 'PLANNED',
    version: 0,
  },
];

const SITES: AdminSite[] = [
  {
    id: 'site-a',
    projectId: 'p1',
    code: 'KSN-A',
    name: 'Kausani Main Block',
    status: 'ACTIVE',
    standardShiftHours: 7,
    monthlyWageDays: 26,
    version: 0,
  },
];

const USERS: PageResponse<AdminUser> = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

function renderPage(projects: AdminProject[] = PROJECTS) {
  get.mockImplementation((url: string) => {
    if (url === '/projects') return Promise.resolve({ data: { ...USERS, content: projects } });
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/users') return Promise.resolve({ data: USERS });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ProjectsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Both views are rendered and CSS decides which one a screen sees, so jsdom — which does not
 * resolve the breakpoint — holds both at once. Every query has to say which it means.
 */
const table = () => within(screen.getByRole('table'));
const cards = () => within(screen.getByRole('list', { name: 'Projects' }));

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
  });

  it('shows which projects have no site to record anything against', async () => {
    renderPage();
    await screen.findAllByText('KSN01');
    const withSite = table().getByText('KSN01').closest('tr') as HTMLElement;
    const withoutSite = table().getByText('BAG02').closest('tr') as HTMLElement;

    expect(within(withSite).getByText('1')).toBeInTheDocument();
    expect(within(withoutSite).getByText('None yet')).toBeInTheDocument();
  });

  it('says the same thing on a phone, where the table would scroll off the screen', async () => {
    renderPage();
    await screen.findAllByText('KSN01');

    const withSite = cards().getByText('KSN01').closest('li') as HTMLElement;
    const withoutSite = cards().getByText('BAG02').closest('li') as HTMLElement;

    // The two facts the table put in columns nobody could reach on a phone.
    expect(within(withSite).getByText('1 site')).toBeInTheDocument();
    expect(within(withSite).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(withoutSite).getByText('No sites yet')).toBeInTheDocument();
  });

  it('edits from a card, not just from a row', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    const card = cards().getByText('KSN01').closest('li') as HTMLElement;
    await user.click(within(card).getByRole('button', { name: 'Edit' }));

    expect(await screen.findByLabelText('Project code')).toBeDisabled();
  });

  it('adds a project from a code and a name alone', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    // Adding now opens on a choice: start from the tender PDF, or type it in.
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.click(screen.getByRole('button', { name: 'Add project' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { code: string; name: string; contractValue?: number },
    ];
    expect(url).toBe('/projects');
    expect(body.code).toBe('ALM03');
    // A blank amount is unknown, not zero — the server must not be told the contract is nil.
    expect(body.contractValue).toBeUndefined();
  });

  it('rejects an amount that is not a number before sending it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    // Adding now opens on a choice: start from the tender PDF, or type it in.
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.type(screen.getByLabelText('Contract value (optional)'), '18.5 lakh');
    await user.click(screen.getByRole('button', { name: 'Add project' }));

    expect(await screen.findByText('A number, up to two decimals')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  it('sends the version and the code-free body when editing', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');
    const row = table().getByText('KSN01').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));

    expect(await screen.findByLabelText('Project code')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [
      string,
      { code?: string; version: number; contractValue?: number; status: string },
    ];
    expect(url).toBe('/projects/p1');
    expect(body.version).toBe(1);
    expect(body.code).toBeUndefined();
    expect(body.contractValue).toBe(18500000);
    expect(body.status).toBe('ACTIVE');
  });

  it('points at the next step when there are no projects at all', async () => {
    renderPage([]);
    // Once in each view; both must point at the same next step.
    expect(await screen.findAllByText(/Add the contract you are working under/)).toHaveLength(2);
  });
});

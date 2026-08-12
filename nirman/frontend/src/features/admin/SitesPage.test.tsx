import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SitesPage } from './SitesPage';
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
    user: { id: 'u-admin', permissions: ['site:read', 'site:write'] },
    hasPermission: (code: string) => ['site:read', 'site:write'].includes(code),
  }),
}));

const PROJECTS: AdminProject[] = [
  { id: 'p1', code: 'KSN01', name: 'Kausani Guest House', status: 'ACTIVE', version: 0 },
];

const SITES: AdminSite[] = [
  {
    id: 'site-a',
    projectId: 'p1',
    code: 'KSN-A',
    name: 'Kausani Main Block',
    siteEngineerIds: ['u-eng'],
    supervisorIds: ['u-sup'],
    status: 'ACTIVE',
    standardShiftHours: 7,
    monthlyWageDays: 26,
    usesOutsourcedLabour: false,
    version: 2,
  },
];

function member(id: string, username: string, fullName: string, role: string): AdminUser {
  return {
    id,
    username,
    fullName,
    active: true,
    mustChangePassword: false,
    roles: [role],
    siteIds: [],
    version: 0,
  };
}

const ENGINEERS = [member('u-eng', 'uttam', 'Uttam Rana', 'ENGINEER')];
const SUPERVISORS = [
  member('u-sup', 'vivek', 'Vivek Aggarwal', 'SUPERVISOR'),
  member('u-sup2', 'ramesh', 'Ramesh Negi', 'SUPERVISOR'),
];

function page(content: AdminUser[]): PageResponse<AdminUser> {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages: 1, first: true, last: true };
}

function renderPage(route = '/sites') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <SitesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Card and table are both in the DOM in jsdom — media queries do not hide either — so
 *  anything matched by text is scoped to the table, the way the projects tests do it. */
const table = () => within(screen.getByRole('table'));
const cards = () => within(screen.getByRole('list', { name: 'Sites' }));

describe('SitesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string, config?: { params?: { role?: string } }) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/projects') return Promise.resolve({ data: { ...page([]), content: PROJECTS } });
      if (url === '/users') {
        const role = config?.params?.role;
        return Promise.resolve({
          data: page(role === 'ENGINEER' ? ENGINEERS : role === 'SUPERVISOR' ? SUPERVISORS : []),
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
  });

  it('names each site’s engineer and supervisor', async () => {
    renderPage();
    await screen.findAllByText('KSN-A');
    const row = table().getByText('KSN-A').closest('tr') as HTMLElement;
    expect(within(row).getByText('Uttam Rana')).toBeInTheDocument();
    expect(within(row).getByText('Vivek Aggarwal')).toBeInTheDocument();
  });

  /** The phone list is the same register, not a summary of it: every fact and both buttons. */
  it('carries the posting and the buttons onto the card', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN-A');

    const card = cards().getByText('KSN-A').closest('li') as HTMLElement;
    expect(within(card).getByText('Uttam Rana')).toBeInTheDocument();
    expect(within(card).getByText('Vivek Aggarwal')).toBeInTheDocument();
    expect(within(card).getByText('KSN01 · 7 h shift')).toBeInTheDocument();

    await user.click(within(card).getByRole('button', { name: 'Edit' }));
    expect(await screen.findByLabelText('Site code')).toBeDisabled();
  });

  it('posts a new site with the engineer who is being given it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN-A');

    await user.click(screen.getByRole('button', { name: 'Add a site' }));
    await user.click(await screen.findByRole('combobox', { name: 'Project' }));
    await user.click(await screen.findByRole('option', { name: /KSN01/ }));
    await user.type(screen.getByLabelText('Site code'), 'KSN-C');
    await user.type(screen.getByLabelText('Site name'), 'Kausani Rear Block');
    await user.click(await screen.findByRole('checkbox', { name: 'Uttam Rana (uttam)' }));
    await user.click(screen.getByRole('button', { name: 'Add site' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { code: string; siteEngineerIds: string[] }];
    expect(url).toBe('/sites');
    expect(body.code).toBe('KSN-C');
    // This id is the whole point of the screen: it is what grants Uttam the site.
    expect(body.siteEngineerIds).toEqual(['u-eng']);
  });

  /** Two shifts, two supervisors. The register could not describe this site at all before. */
  it('posts every supervisor named, not just the first', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN-A');

    await user.click(screen.getByRole('button', { name: 'Add a site' }));
    await user.click(await screen.findByRole('combobox', { name: 'Project' }));
    await user.click(await screen.findByRole('option', { name: /KSN01/ }));
    await user.type(screen.getByLabelText('Site code'), 'KSN-D');
    await user.type(screen.getByLabelText('Site name'), 'Two Shift Block');
    await user.click(screen.getByRole('checkbox', { name: 'Vivek Aggarwal (vivek)' }));
    await user.click(screen.getByRole('checkbox', { name: 'Ramesh Negi (ramesh)' }));
    await user.click(screen.getByRole('button', { name: 'Add site' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { supervisorIds: string[] }];
    expect(body.supervisorIds).toEqual(['u-sup', 'u-sup2']);
  });

  it('offers only members who hold the role the slot is for', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN-A');
    await user.click(screen.getByRole('button', { name: 'Add a site' }));

    const engineers = within(await screen.findByRole('group', { name: 'Site engineers' }));
    // Vivek is a supervisor; the server would refuse him here, so the picker does not offer him.
    expect(engineers.getByRole('checkbox', { name: 'Uttam Rana (uttam)' })).toBeInTheDocument();
    expect(
      engineers.queryByRole('checkbox', { name: 'Vivek Aggarwal (vivek)' }),
    ).not.toBeInTheDocument();
  });

  it('sends a cleared supervisor as an empty list, which withdraws their access', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN-A');
    const row = table().getByText('KSN-A').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));

    // Ticked because he is named on the site; untick is how he is taken off it.
    await user.click(await screen.findByRole('checkbox', { name: 'Vivek Aggarwal (vivek)' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [
      string,
      { supervisorIds: string[]; siteEngineerIds: string[]; version: number },
    ];
    expect(url).toBe('/sites/site-a');
    expect(body.supervisorIds).toEqual([]);
    expect(body.siteEngineerIds).toEqual(['u-eng']);
    expect(body.version).toBe(2);
  });

  /**
   * Arriving from a project's Sites button. The admin has already said which project they
   * mean; asking again on arrival is the screen forgetting how they got here.
   */
  it('opens on the project it was sent from, and narrows the list to it', async () => {
    renderPage('/sites?projectId=p1');
    await screen.findAllByText('KSN-A');

    expect(screen.getByRole('combobox', { name: 'Project' })).toHaveTextContent('KSN01');
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/sites', {
        params: { projectId: 'p1', deleted: undefined },
      }),
    );
  });

  it('opens unnarrowed when it was reached directly', async () => {
    renderPage();
    await screen.findAllByText('KSN-A');

    // MUI leaves the picker blank on the empty option rather than printing its label, so
    // the fact worth asserting is the request: no project named, every site listed.
    expect(get).toHaveBeenCalledWith('/sites', {
      params: { projectId: undefined, deleted: undefined },
    });
  });
});

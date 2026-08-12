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

/** An administrator: the only role the migration grants project:delete. */
const PERMISSIONS = ['project:read', 'project:write', 'project:delete'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: PERMISSIONS },
    hasPermission: (code: string) => PERMISSIONS.includes(code),
  }),
}));

const LIVE: AdminProject[] = [
  { id: 'p1', code: 'KSN01', name: 'Kausani Guest House', status: 'ACTIVE', version: 1 },
];

const DELETED: AdminProject[] = [
  {
    id: 'p9',
    code: 'DUP01',
    name: 'Entered twice',
    status: 'PLANNED',
    deletedAt: '2026-03-04T09:15:00Z',
    deletedBy: 'u-admin',
    deletedReason: 'duplicate of KSN01',
    version: 2,
  },
];

const LIVE_SITES: AdminSite[] = [
  {
    id: 'site-a',
    projectId: 'p1',
    code: 'KSN-A',
    name: 'Kausani Main Block',
    status: 'ACTIVE',
    standardShiftHours: 7,
    monthlyWageDays: 26,
    usesOutsourcedLabour: false,
    siteEngineerIds: [],
    supervisorIds: [],
    version: 0,
  },
];

const EMPTY_USERS: PageResponse<AdminUser> = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

function page(content: AdminProject[]) {
  return { ...EMPTY_USERS, content };
}

function renderPage() {
  get.mockImplementation((url: string, config?: { params?: Record<string, unknown> }) => {
    const deleted = config?.params?.deleted === true;
    if (url === '/projects') {
      return Promise.resolve({ data: page(deleted ? DELETED : LIVE) });
    }
    if (url === '/sites') {
      return Promise.resolve({ data: deleted ? [] : LIVE_SITES });
    }
    if (url === '/users') return Promise.resolve({ data: EMPTY_USERS });
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

const table = () => within(screen.getByRole('table'));

/**
 * The deleted register.
 *
 * <p>The rule this holds down is that a deleted project is somewhere else, not somewhere
 * dimmer: it never appears on the ordinary list a dashboard or a picker reads, and getting
 * to it is a deliberate act by somebody who could undo it.</p>
 */
describe('ProjectsPage — deleted projects', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
    del.mockResolvedValue({ data: {} });
  });

  it('keeps deleted projects off the ordinary list until they are asked for', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');
    expect(screen.queryByText('DUP01')).toBeNull();

    await user.click(screen.getByRole('checkbox', { name: 'Deleted' }));

    // The swap, not a widening: the live contract goes away with the same click.
    expect(await screen.findAllByText('DUP01')).not.toHaveLength(0);
    await waitFor(() => expect(screen.queryByText('KSN01')).toBeNull());
  });

  it('shows why it went and who can put it back, in place of the status', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');
    await user.click(screen.getByRole('checkbox', { name: 'Deleted' }));

    const row = (await screen.findAllByText('DUP01'))
      .map((node) => node.closest('tr'))
      .find((node): node is HTMLTableRowElement => node !== null) as HTMLElement;

    expect(within(row).getByText('duplicate of KSN01')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Restore' })).toBeInTheDocument();
    // Nothing to edit on a project that is off the books.
    expect(within(row).queryByRole('button', { name: 'Edit' })).toBeNull();

    await user.click(within(row).getByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(post).toHaveBeenCalledWith('/projects/p9/restore'));
  });

  it('will not delete without a reason, and warns which sites go too', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    const row = table().getByText('KSN01').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete' }));

    // The cascade is stated before the fact, not discovered afterwards.
    expect(await screen.findByText(/Its site goes with it: KSN-A/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Delete project' }));
    expect(await screen.findByText(/Say why this is being deleted/)).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText(/Why is it being deleted/), 'duplicate of BAG02');
    await user.click(screen.getByRole('button', { name: 'Delete project' }));

    await waitFor(() => expect(del).toHaveBeenCalledOnce());
    expect(del).toHaveBeenCalledWith('/projects/p1', {
      data: { reason: 'duplicate of BAG02' },
    });
  });

  it("puts the server's refusal in front of the admin who has to act on it", async () => {
    const user = userEvent.setup({ delay: null });
    // What the server says when the site has real work on it. The count is the whole
    // value of the message, so it has to survive as far as the screen.
    del.mockRejectedValue({
      // apiErrorDetail reads the RFC 7807 body off an axios error, and only off an axios
      // error — without the flag it falls back to "Something went wrong" and the count,
      // which is the whole point of the message, never reaches the screen.
      isAxiosError: true,
      response: {
        data: {
          detail:
            'This site has 6 attendance records and 1 expense recorded against it. ' +
            'Work already booked cannot be taken off the books.',
        },
      },
    });
    renderPage();
    await screen.findAllByText('KSN01');

    const row = table().getByText('KSN01').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Delete' }));
    await user.type(screen.getByLabelText(/Why is it being deleted/), 'cleaning up');
    await user.click(screen.getByRole('button', { name: 'Delete project' }));

    expect(await screen.findByText(/6 attendance records/)).toBeInTheDocument();
    // The dialog stays open: the refusal is the answer, and closing would hide it.
    expect(screen.getByRole('button', { name: 'Delete project' })).toBeInTheDocument();
  });
});

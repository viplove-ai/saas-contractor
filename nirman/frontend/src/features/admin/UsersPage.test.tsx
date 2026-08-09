import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { UsersPage } from './UsersPage';
import type { AdminSite, AdminUser, PageResponse, RoleOption } from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const patch = vi.fn();

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
      patch: (...args: unknown[]) => patch(...args),
    },
  };
});

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: ['user:read', 'user:write'] },
    hasPermission: (code: string) => ['user:read', 'user:write'].includes(code),
  }),
}));

const ROLES: RoleOption[] = [
  { code: 'ADMIN', name: 'Administrator' },
  { code: 'ENGINEER', name: 'Site Engineer' },
  { code: 'SUPERVISOR', name: 'Site Supervisor' },
  { code: 'ACCOUNTANT', name: 'Accountant' },
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
  content: [
    {
      id: 'u-admin',
      username: 'viplove',
      fullName: 'Viplove Chaudhary',
      mobile: '+91-9800000001',
      active: true,
      mustChangePassword: false,
      roles: ['ADMIN'],
      siteIds: [],
      version: 0,
    },
    {
      id: 'u-super',
      username: 'vivek',
      fullName: 'Vivek Aggarwal',
      mobile: '+91-9800000003',
      active: true,
      mustChangePassword: true,
      roles: ['SUPERVISOR'],
      siteIds: ['site-a'],
      version: 3,
    },
  ],
  page: 0,
  size: 100,
  totalElements: 2,
  totalPages: 1,
  first: true,
  last: true,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('UsersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/users') return Promise.resolve({ data: USERS });
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/roles') return Promise.resolve({ data: ROLES });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
    patch.mockResolvedValue({ data: {} });
  });

  it('separates a company-wide role from a site-scoped one', async () => {
    renderPage();
    const supervisorRow = (await screen.findByText('Vivek Aggarwal')).closest(
      'tr',
    ) as HTMLElement;
    const adminRow = screen.getByText('Viplove Chaudhary').closest('tr') as HTMLElement;

    expect(within(supervisorRow).getByText('KSN-A')).toBeInTheDocument();
    // An admin's blank posting list would otherwise read as "no access at all".
    expect(within(adminRow).getByText('All sites')).toBeInTheDocument();
    expect(within(supervisorRow).getByText('Password not set')).toBeInTheDocument();
  });

  it('onboards a member with a first-time password and one role', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Vivek Aggarwal');

    await user.click(screen.getByRole('button', { name: 'Onboard a member' }));
    await user.type(screen.getByLabelText('Username'), 'ramesh.k');
    await user.type(screen.getByLabelText('Full name'), 'Ramesh Kumar');
    await user.type(screen.getByLabelText('Mobile'), '+91-9800000123');
    await user.click(screen.getByRole('checkbox', { name: 'KSN-A — Kausani Main Block' }));
    await user.click(screen.getByRole('button', { name: 'Onboard member' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { username: string; mobile: string; roleCodes: string[]; temporaryPassword: string },
    ];
    expect(url).toBe('/users');
    expect(body.username).toBe('ramesh.k');
    expect(body.mobile).toBe('+91-9800000123');
    expect(body.roleCodes).toEqual(['SUPERVISOR']);
    // Pre-filled rather than left to the admin's imagination, and long enough to matter.
    expect(body.temporaryPassword.length).toBeGreaterThanOrEqual(8);
  });

  // A supervisor with no posting signs in to an empty app: no site in any picker and a dead
  // "Take on a worker" button. It is allowed — an engineer exists before the site he runs —
  // but the admin is told, not left to hear about it from the field.
  it('warns while a site-scoped member has no posting', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Vivek Aggarwal');

    await user.click(screen.getByRole('button', { name: 'Onboard a member' }));
    expect(await screen.findByText(/Not posted anywhere yet/)).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'KSN-A — Kausani Main Block' }));
    await waitFor(() => expect(screen.queryByText(/Not posted anywhere yet/)).toBeNull());
  });

  it('drops site postings when a member is moved to a company-wide role', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Vivek Aggarwal');

    const supervisorRow = screen.getByText('Vivek Aggarwal').closest('tr') as HTMLElement;
    await user.click(within(supervisorRow).getByRole('button', { name: 'Edit' }));
    await user.click(await screen.findByRole('combobox', { name: 'Role' }));
    await user.click(await screen.findByRole('option', { name: 'Accountant' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledWith('/users/u-super/sites', { siteIds: [] }));
    expect(put).toHaveBeenCalledWith('/users/u-super/roles', { roleCodes: ['ACCOUNTANT'] });
  });

  it('shows the reset password once, after the reset has gone through', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Vivek Aggarwal');

    const supervisorRow = screen.getByText('Vivek Aggarwal').closest('tr') as HTMLElement;
    await user.click(within(supervisorRow).getByRole('button', { name: 'Reset password' }));

    const field = (await screen.findByLabelText('New temporary password')) as HTMLInputElement;
    const generated = field.value;
    expect(generated.length).toBeGreaterThanOrEqual(8);

    // Scoped to the dialog: the row that opened it still carries a button of that name.
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset password' }));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/users/u-super/password/reset', {
        temporaryPassword: generated,
      }),
    );

    // The server never returns a password, so this dialog is the only place it can be read.
    const handover = (await screen.findByLabelText(
      'Password to hand over',
    )) as HTMLInputElement;
    expect(handover.value).toBe(generated);
  });

  it('refuses to let an admin disable the account they are signed in with', async () => {
    renderPage();
    await screen.findByText('Vivek Aggarwal');
    const adminRow = screen.getByText('Viplove Chaudhary').closest('tr') as HTMLElement;
    // The signed-in user is u-admin in this fixture; the row is viplove's, id u-admin.
    expect(within(adminRow).getByRole('button', { name: 'Disable' })).toBeDisabled();
  });
});

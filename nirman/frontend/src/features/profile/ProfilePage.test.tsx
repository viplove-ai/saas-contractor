import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { UpdateCheck } from '../../shared/appUpdate';
import type { SessionUser } from '../../shared/session';
import { ProfilePage } from './ProfilePage';

const changePassword = vi.fn();
const updateUser = vi.fn();
let currentUser: SessionUser;

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: currentUser, changePassword, updateUser }),
}));

/*
  The signature card asks for a signed link to the picture on file. Answered here so the test
  never reaches for a network, and so the card renders the image rather than the not-loading line.
*/
vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: vi.fn(async () => ({ data: { url: 'https://storage.test/sig.png', fileName: 'sig.png' } })),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    },
  };
});

const check = vi.fn();
const install = vi.fn();
let updateState: { ready: boolean; lastCheck: UpdateCheck };

vi.mock('../../shared/appUpdate', () => ({
  useAppUpdate: () => ({ ...updateState, check, install, postpone: vi.fn() }),
}));

function sessionUser(overrides: Partial<SessionUser> = {}): SessionUser {
  return {
    id: 'u-super',
    username: 'vivek',
    fullName: 'Vivek Aggarwal',
    mustChangePassword: false,
    roles: ['SUPERVISOR'],
    permissions: ['attendance:create'],
    siteIds: ['site-a'],
    allSites: false,
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    currentUser = sessionUser();
    changePassword.mockResolvedValue(sessionUser());
    updateState = { ready: false, lastCheck: 'IDLE' };
  });

  it('changes the password once the two new entries agree', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.type(screen.getByLabelText('Current password'), 'OldPass@123');
    await user.type(screen.getByLabelText('New password'), 'BrandNew@456');
    await user.type(screen.getByLabelText('New password again'), 'BrandNew@456');
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(changePassword).toHaveBeenCalledWith('OldPass@123', 'BrandNew@456'));
    expect(await screen.findByText('Your password has been changed.')).toBeInTheDocument();
  });

  it('refuses a mistyped confirmation without troubling the server', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.type(screen.getByLabelText('Current password'), 'OldPass@123');
    await user.type(screen.getByLabelText('New password'), 'BrandNew@456');
    await user.type(screen.getByLabelText('New password again'), 'BrandNew@457');
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByText('The two passwords do not match')).toBeInTheDocument();
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('offers to collect the signature the account is missing', () => {
    renderPage();

    expect(screen.getByText(/No signature on file yet/)).toBeInTheDocument();
    expect(screen.getByText('Photograph signature')).toBeInTheDocument();
    expect(screen.getByText('Choose a photo')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  it('shows the signature on file and offers to replace or remove it', async () => {
    currentUser = sessionUser({ signatureAttachmentId: 'att-sig', outstanding: [] });
    renderPage();

    expect(screen.queryByText(/No signature on file yet/)).not.toBeInTheDocument();
    expect(await screen.findByRole('img', { name: 'Your signature' })).toHaveAttribute(
      'src',
      'https://storage.test/sig.png',
    );
    expect(screen.getByText('Photograph again')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
  });

  it('says why a member on an admin-issued password has been sent here', async () => {
    currentUser = sessionUser({ mustChangePassword: true });
    renderPage();

    expect(screen.getByText(/still using the password an administrator gave you/)).toBeInTheDocument();
    // The field is labelled for what they actually hold, not for a password they chose.
    expect(screen.getByLabelText('The password you were given')).toBeInTheDocument();
  });
});

/**
 * The update is on this screen because the snackbar that offers it can be missed: it is
 * dismissed once, on a phone that is never closed, and the version sits parked for a week
 * while everyone assumes the app updates itself. This is the place somebody can go and ask.
 */
describe('the app version card', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    currentUser = sessionUser();
    updateState = { ready: false, lastCheck: 'IDLE' };
  });

  it('asks the server when there is no offer standing', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Check for updates' }));

    expect(check).toHaveBeenCalled();
    expect(install).not.toHaveBeenCalled();
  });

  it('reports an app that is already current', () => {
    updateState = { ready: false, lastCheck: 'CURRENT' };
    renderPage();

    expect(screen.getByText('You have the latest version.')).toBeInTheDocument();
  });

  /** No answer is the ordinary state of a site afternoon, and says nothing about the app. */
  it('does not dress a failed check up as a fault', () => {
    updateState = { ready: false, lastCheck: 'UNREACHABLE' };
    renderPage();

    expect(screen.getByText(/no connection to the server/)).toBeInTheDocument();
  });

  it('offers the handover once a version is waiting', async () => {
    const user = userEvent.setup({ delay: null });
    updateState = { ready: true, lastCheck: 'IDLE' };
    renderPage();

    expect(screen.getByText(/Anything you have not sent yet is kept/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Update now' }));

    expect(install).toHaveBeenCalled();
    expect(check).not.toHaveBeenCalled();
  });
});

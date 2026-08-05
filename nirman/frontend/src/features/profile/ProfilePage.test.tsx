import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionUser } from '../../shared/session';
import { ProfilePage } from './ProfilePage';

const changePassword = vi.fn();
let currentUser: SessionUser;

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: currentUser, changePassword }),
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
  return render(
    <MemoryRouter>
      <ProfilePage />
    </MemoryRouter>,
  );
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    currentUser = sessionUser();
    changePassword.mockResolvedValue(sessionUser());
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

  it('says why a member on an admin-issued password has been sent here', async () => {
    currentUser = sessionUser({ mustChangePassword: true });
    renderPage();

    expect(screen.getByText(/still using the password an administrator gave you/)).toBeInTheDocument();
    // The field is labelled for what they actually hold, not for a password they chose.
    expect(screen.getByLabelText('The password you were given')).toBeInTheDocument();
  });
});

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionUser } from '../../shared/session';
import { SignaturePrompt } from './SignaturePrompt';

let currentUser: SessionUser | null;

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: currentUser }),
}));

function member(overrides: Partial<SessionUser> = {}): SessionUser {
  return {
    id: 'u-super',
    username: 'vivek',
    fullName: 'Vivek Aggarwal',
    mustChangePassword: false,
    roles: ['SUPERVISOR'],
    permissions: [],
    siteIds: ['site-a'],
    allSites: false,
    outstanding: ['SIGNATURE'],
    ...overrides,
  };
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SignaturePrompt />
      <Routes>
        <Route path="/today" element={<div>Today</div>} />
        <Route path="/profile" element={<div>Profile screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SignaturePrompt', () => {
  beforeEach(() => {
    sessionStorage.clear();
    currentUser = member();
  });

  it('asks for a missing signature and sends the member to the card', async () => {
    const user = userEvent.setup({ delay: null });
    renderAt('/today');

    expect(screen.getByText('Your signature is not on file')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Upload now' }));

    expect(await screen.findByText('Profile screen')).toBeInTheDocument();
    expect(screen.queryByText('Your signature is not on file')).not.toBeInTheDocument();
  });

  it('is dismissed for the sitting, and only for this member', async () => {
    const user = userEvent.setup({ delay: null });
    const { unmount } = renderAt('/today');
    await user.click(screen.getByRole('button', { name: 'Later' }));
    expect(screen.queryByText('Your signature is not on file')).not.toBeInTheDocument();
    unmount();

    // The same person again: still dismissed.
    renderAt('/today');
    expect(screen.queryByText('Your signature is not on file')).not.toBeInTheDocument();
  });

  it('says nothing once the server has nothing outstanding', () => {
    currentUser = member({ outstanding: [], signatureAttachmentId: 'att-1' });
    renderAt('/today');
    expect(screen.queryByText('Your signature is not on file')).not.toBeInTheDocument();
  });

  it('stays quiet on the profile screen, where the card already is', () => {
    renderAt('/profile');
    expect(screen.queryByText('Your signature is not on file')).not.toBeInTheDocument();
  });
});

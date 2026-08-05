import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionUser } from '../shared/session';
import { RootLayout } from './RootLayout';

let user: SessionUser | null = null;

vi.mock('../features/auth/AuthContext', () => ({
  useAuth: () => ({
    user,
    initialising: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
    hasPermission: () => true,
  }),
}));

// The banner reads Dexie, which has nothing to say in a unit test and needs no fake here.
vi.mock('../shared/OfflineBanner', () => ({ OfflineBanner: () => null }));

const SIGNED_IN = {
  fullName: 'Karam Singh',
  roles: ['Engineer'],
  permissions: [],
  siteIds: ['site-a'],
  allSites: false,
} as unknown as SessionUser;

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<RootLayout />}>
          <Route path="/home" element={<p>home screen</p>} />
          <Route path="/attendance/verify" element={<p>verify screen</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('RootLayout', () => {
  beforeEach(() => {
    user = SIGNED_IN;
  });

  it('offers a way home from a task screen', () => {
    renderAt('/attendance/verify');

    const home = screen.getByRole('link', { name: 'Go to home' });
    expect(home).toHaveAttribute('href', '/home');
  });

  it('also takes the wordmark home, for the desk habit of clicking the brand', () => {
    renderAt('/attendance/verify');

    expect(screen.getByRole('link', { name: 'Nirman' })).toHaveAttribute('href', '/home');
  });

  it('does not point home while already on it', () => {
    renderAt('/home');

    expect(screen.queryByRole('link', { name: 'Go to home' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Nirman' })).not.toBeInTheDocument();
    expect(screen.getByText('Nirman')).toBeInTheDocument();
  });

  it('shows no way home to a visitor who is not signed in', () => {
    user = null;
    renderAt('/attendance/verify');

    expect(screen.queryByRole('link', { name: 'Go to home' })).not.toBeInTheDocument();
  });
});

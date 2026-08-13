import { render, screen, within } from '@testing-library/react';
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
          <Route path="/today" element={<p>today screen</p>} />
          <Route path="/home" element={<p>home screen</p>} />
          <Route path="/attendance/verify" element={<p>verify screen</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Both shells are in the tree at once — which one a person sees is a media query, and jsdom
 * resolves none. The rail is rendered first and the bottom bar last, so the bar is the one
 * at the end of the list.
 */
function bottomBar(): HTMLElement {
  const navs = screen.getAllByRole('navigation', { name: 'Main' });
  return navs[navs.length - 1]!;
}

describe('RootLayout', () => {
  beforeEach(() => {
    user = SIGNED_IN;
  });

  /**
   * Navigation used to be one home icon on an AppBar, because the tile grid was the only way
   * back from a task screen. The bar carries the five destinations directly, so neither the
   * AppBar nor the icon has anything left to do.
   */
  it('carries no AppBar and no way-home icon', () => {
    renderAt('/attendance/verify');

    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Go to home' })).not.toBeInTheDocument();
  });

  it('offers the five main destinations from any task screen', () => {
    renderAt('/attendance/verify');

    expect(
      within(bottomBar())
        .getAllByRole('link')
        .map((link) => link.textContent?.trim()),
    ).toEqual(['Today', 'Registers', 'Sign-off', 'Reports', 'More']);
  });

  it('marks the section the current screen belongs to', () => {
    renderAt('/attendance/verify');

    const bar = within(bottomBar());
    expect(bar.getByRole('link', { name: 'Sign-off' })).toHaveAttribute('aria-current', 'page');
    expect(bar.getByRole('link', { name: 'Today' })).not.toHaveAttribute('aria-current');
  });

  it('takes the masthead mark back to Today', () => {
    renderAt('/attendance/verify');

    expect(screen.getByRole('link', { name: 'Nirman' })).toHaveAttribute('href', '/today');
  });

  it('shows no account link to a visitor who is not signed in', () => {
    user = null;
    renderAt('/attendance/verify');

    expect(screen.queryByRole('link', { name: 'Your account' })).not.toBeInTheDocument();
  });

  it('puts the account one tap away for somebody who is signed in', () => {
    renderAt('/attendance/verify');

    expect(screen.getByRole('link', { name: 'Your account' })).toHaveAttribute('href', '/profile');
  });
});

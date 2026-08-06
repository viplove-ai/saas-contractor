import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HomePage } from './HomePage';

let permissions: string[] = [];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'u-sup',
      fullName: 'Vivek Bisht',
      roles: ['SUPERVISOR'],
      siteIds: ['site-a'],
      allSites: false,
      permissions,
    },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SUPERVISOR = [
  'attendance:create',
  'inventory:receive',
  'inventory:issue',
  'inventory:read',
  'expense:create',
  'expense:read',
  'dpr:draft',
  'worker:read',
];

const ENGINEER = ['attendance:verify', 'dpr:verify', 'inventory:read', 'dashboard:site'];

const ADMIN = [
  ...SUPERVISOR,
  ...ENGINEER,
  'user:read',
  'project:write',
  'site:write',
  'dashboard:company',
  'dashboard:dataquality',
];

function renderHome() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  );
}

/** The tiles inside one named group, in the order the screen puts them. */
function tilesUnder(heading: string): string[] {
  const section = screen.getByRole('heading', { name: heading }).closest('section');
  expect(section).not.toBeNull();
  return within(section!)
    .getAllByRole('link')
    .map((link) => link.textContent?.trim() ?? '');
}

describe('HomePage', () => {
  beforeEach(() => {
    permissions = [];
  });

  /**
   * The grouping is by act, not by module, and this is the assertion that says so: marking
   * attendance and receiving material are different modules and the same job, so they sit
   * together — while verifying attendance is the same module as marking it and belongs
   * somewhere else entirely.
   */
  it('groups the day-entry screens together regardless of which module they belong to', () => {
    permissions = ADMIN;
    renderHome();

    expect(tilesUnder('Today at site')).toEqual([
      'Mark attendance',
      'Receive material',
      'Issue material',
      'Add expense',
      'Daily report',
    ]);
    expect(tilesUnder('Check and sign off')).toEqual([
      'Verify attendance',
      'Verify reports',
      'Approvals and payments',
    ]);
  });

  /**
   * The heading has to go with the tiles. Showing a supervisor the word "Set up" over an
   * empty space reads as something broken, rather than as something that was never his.
   */
  it('drops a whole group, heading and all, when the role can open nothing in it', () => {
    permissions = SUPERVISOR;
    renderHome();

    expect(screen.getByRole('heading', { name: 'Today at site' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Set up' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'How it is going' })).not.toBeInTheDocument();
    expect(screen.queryByText('Projects')).not.toBeInTheDocument();
  });

  it('shows an engineer the sign-off group and not the entry screens he does not use', () => {
    permissions = ENGINEER;
    renderHome();

    expect(tilesUnder('Check and sign off')).toEqual(['Verify attendance', 'Verify reports']);
    expect(screen.queryByRole('heading', { name: 'Today at site' })).not.toBeInTheDocument();
    // He can still read the registers, which is a different group and a different act.
    expect(tilesUnder('Look something up')).toEqual(['Stock']);
  });

  /**
   * Two tiles need no permission at all, and they are the two about the app rather than
   * about the work — so the last group is the one thing every signed-in person sees.
   */
  it('always offers the device group, whatever the role can do', () => {
    permissions = [];
    renderHome();

    expect(tilesUnder('This device')).toEqual(['Not sent yet', 'Your account']);
  });

  it('names who is signed in and how much of the company they can see', () => {
    permissions = SUPERVISOR;
    renderHome();

    expect(screen.getByText(/Vivek Bisht/)).toBeVisible();
    expect(screen.getByText(/1 site\(s\) assigned/)).toBeVisible();
  });

  /** It is the index of the product now, not the landing screen, and it says so. */
  it('is titled as the index rather than as the app', () => {
    permissions = SUPERVISOR;
    renderHome();

    expect(screen.getByRole('heading', { name: 'All screens' })).toBeVisible();
  });
});

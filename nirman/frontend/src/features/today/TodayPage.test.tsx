import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AttendanceRecord, Roster, RosterEntry } from '../attendance/types';
import type { SiteDashboard } from '../dashboard/types';
import { TodayPage } from './TodayPage';

/**
 * The screen composes queries that already exist rather than calling a `/today` endpoint, so
 * the hooks are mocked at the module boundary and `useToday` itself runs for real — the
 * counts on this screen are its arithmetic, not the server's.
 */
let roster: Roster;
let dashboard: SiteDashboard | undefined;
let verificationRows: number;
/** What the signed-in account may do. A supervisor holds neither of the two below. */
let permissions: string[];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-1', fullName: 'Vivek Bisht', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

vi.mock('../attendance/api', () => ({
  useSites: () => ({
    data: [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Block A', standardShiftHours: 8 }],
  }),
  useRoster: () => ({ data: roster, isLoading: false }),
  useVerificationQueue: () => ({
    data: { content: Array.from({ length: verificationRows }, () => ({})) },
  }),
}));

vi.mock('../dashboard/api', async (importOriginal) => ({
  // iso() and monthToDate() are plain date arithmetic and are wanted as they are.
  ...(await importOriginal<typeof import('../dashboard/api')>()),
  useSiteDashboard: () => ({ data: dashboard, isLoading: false, isError: false, error: null }),
}));

function entry(workerId: string, marked: boolean): RosterEntry {
  const base: RosterEntry = {
    workerId,
    workerCode: `W-${workerId}`,
    workerName: `Worker ${workerId}`,
  };
  // Only `status` is read here; the rest of the record is the server's business.
  return marked ? { ...base, attendance: { status: 'PRESENT' } as AttendanceRecord } : base;
}

function rosterOf(...entries: RosterEntry[]): Roster {
  return {
    siteId: 'site-a',
    date: '2026-08-06',
    standardShiftHours: 8,
    overtimeReasonRequiredAboveHours: 9,
    periodLocked: false,
    entries,
  };
}

/** Only the four figures the screen reads are filled; the rest are zero on purpose. */
function dashboardWith(overrides: {
  awaitingApproval?: number;
  draft?: number;
  awaitingVerification?: number;
}): SiteDashboard {
  return {
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Kausani Block A',
    projectId: 'p-1',
    from: '2026-08-01',
    to: '2026-08-06',
    labour: {
      manDays: 40,
      regularHours: 320,
      overtimeHours: 0,
      cost: 120000,
      verifiedCost: 120000,
      unverifiedCost: 0,
      pendingVerification: 0,
      daysWithAttendance: 5,
      daysWithoutAttendance: 0,
    },
    material: {
      openingValue: 0,
      received: 0,
      consumed: 0,
      inventoryValue: 0,
      residual: 0,
      reconciles: true,
      issued: 0,
      wasted: 0,
      purchased: 0,
      purchasedNotReceived: 0,
      note: '',
    },
    cash: {
      totalBooked: 0,
      costIncurred: 0,
      materialPurchases: 0,
      labourDisbursements: 0,
      paid: 0,
      payable: 0,
      awaitingApproval: overrides.awaitingApproval ?? 0,
    },
    progress: {
      contractValue: 0,
      valueOfWorkDone: 0,
      itemsTotal: 0,
      itemsCompleted: 0,
      itemsInProgress: 0,
      itemsOverClaimed: 0,
    },
    dpr: {
      reportsInRange: 0,
      verified: 0,
      awaitingVerification: overrides.awaitingVerification ?? 0,
      draft: overrides.draft ?? 0,
      daysWithoutReport: [],
    },
    trend: [],
    topWorkItems: [],
    caveat: '',
  };
}

function renderToday() {
  return render(
    <MemoryRouter>
      <TodayPage />
    </MemoryRouter>,
  );
}

describe('TodayPage', () => {
  beforeEach(() => {
    roster = rosterOf(entry('1', false), entry('2', false), entry('3', false));
    dashboard = dashboardWith({});
    verificationRows = 0;
    // The engineer's set by default: the existing cases are about the sign-off band.
    permissions = ['dashboard:site', 'attendance:verify', 'dpr:verify'];
  });

  /**
   * The one thing the screen is loud about, and the reason it exists: wages freeze at
   * verification, so an unmarked muster is a cost figure that is still moving.
   */
  it('says the muster is not marked when nobody on the roster has been', () => {
    renderToday();

    expect(screen.getByText('NOT MARKED YET')).toBeVisible();
  });

  it('counts what has been marked once anybody has', () => {
    roster = rosterOf(entry('1', true), entry('2', false), entry('3', true));
    renderToday();

    expect(screen.getByText('2 OF 3 MARKED')).toBeVisible();
    expect(screen.queryByText('NOT MARKED YET')).not.toBeInTheDocument();
  });

  /** Forty men present is the common day, but only before the roll has been taken. */
  it('offers the mark-all shortcut only while the muster is unmarked', () => {
    renderToday();

    expect(screen.getByRole('link', { name: 'Mark all 3 present' })).toHaveAttribute(
      'href',
      '/attendance/mark?all=present',
    );
  });

  it('drops the mark-all shortcut once the muster has been started', () => {
    roster = rosterOf(entry('1', true), entry('2', false));
    renderToday();

    expect(screen.queryByRole('link', { name: /Mark all/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the muster' })).toBeVisible();
  });

  /**
   * A row saying "0 bill(s) need approval" is a line of work that does not exist. Only the
   * groups with something in them get a row.
   */
  it('leaves out a waiting group whose count is zero', () => {
    verificationRows = 2;
    dashboard = dashboardWith({ awaitingApproval: 0, awaitingVerification: 1 });
    renderToday();

    expect(screen.getByText('2 attendance row(s) to verify')).toBeVisible();
    expect(screen.getByText('1 daily report(s) to verify')).toBeVisible();
    expect(screen.queryByText(/bill\(s\) need approval/)).not.toBeInTheDocument();
    expect(screen.queryByText(/still a draft/)).not.toBeInTheDocument();
  });

  /**
   * The supervisor's version of the screen. He signs nothing off and sees no dashboard, so
   * both bands would be permanently empty furniture — and what is left is his actual day:
   * the muster at the top, the report at the bottom, entry in between.
   */
  describe('for a supervisor', () => {
    beforeEach(() => {
      permissions = ['attendance:create', 'dpr:draft', 'inventory:receive', 'expense:create'];
      dashboard = undefined;
    });

    it('leaves out the sign-off band he can do nothing with', () => {
      renderToday();

      expect(screen.queryByText(/WAITING ON YOU/)).not.toBeInTheDocument();
      expect(screen.queryByText(/SITE, MONTH TO DATE/)).not.toBeInTheDocument();
    });

    it('puts the day\u2019s report on the screen as the end of his day', () => {
      renderToday();

      expect(screen.getByRole('heading', { name: 'Today\u2019s report' })).toBeVisible();
      expect(screen.getByRole('link', { name: "Write today's report" })).toHaveAttribute(
        'href',
        '/dpr/new',
      );
    });

    it('still opens on the muster, which is the start of it', () => {
      renderToday();

      expect(screen.getByRole('heading', { name: 'Muster for today' })).toBeVisible();
      expect(screen.getByRole('link', { name: 'Mark attendance' })).toBeVisible();
    });
  });
});

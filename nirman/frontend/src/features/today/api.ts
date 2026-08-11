import { useMemo } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { useAuth } from '../auth/AuthContext';
import { useRoster, useSites, useVerificationQueue } from '../attendance/api';
import { iso, monthToDate, useSiteDashboard } from '../dashboard/api';
import { offlineDb, UNSENT_STATUSES } from '../../offline/db';

/**
 * Everything the Today screen shows, assembled from queries that already exist.
 *
 * <p>No new endpoint. That is the point of the screen: the figures a supervisor is ranked by
 * at 9 a.m. are already in the roster and the site dashboard, they were just spread across two
 * screens he had to think to open. Composing here rather than adding `/today` on the server
 * also means the screen works from cache on a phone that has not reached the network yet —
 * react-query serves the last roster it saw, and the muster card is still correct about
 * whether anybody has been marked.</p>
 */
export function useToday(siteId: string | undefined) {
  const today = iso(new Date());
  const period = useMemo(monthToDate, []);

  const { hasPermission } = useAuth();
  // A supervisor holds neither. The screen is still his — the muster and the day's entry are
  // his whole job — so the two reads are not fired rather than fired and refused.
  const seesDashboard = hasPermission('dashboard:site');
  const signsOff = hasPermission('attendance:verify');

  const sites = useSites();
  const roster = useRoster(siteId, today);
  const dashboard = useSiteDashboard(siteId, period.from, period.to, seesDashboard);
  // A week back: anything older is a month-end problem, not a this-morning one.
  const toVerify = useVerificationQueue(
    siteId,
    iso(addDays(new Date(), -7)),
    today,
    'SUBMITTED',
    signsOff,
  );

  const unsent = useLiveQuery(
    async () => ({
      waiting: await offlineDb.drafts.where('status').anyOf(UNSENT_STATUSES).count(),
      stuck: await offlineDb.drafts.where('status').anyOf('CONFLICT', 'FAILED').count(),
    }),
    [],
    { waiting: 0, stuck: 0 },
  );

  const entries = roster.data?.entries ?? [];
  const marked = entries.filter((entry) => entry.attendance?.status).length;

  const attendanceRowsToVerify = toVerify.data?.content.length ?? 0;
  const billsToApprove = dashboard.data?.cash.awaitingApproval ?? 0;
  const draftReports = dashboard.data?.dpr.draft ?? 0;
  const reportsToVerify = dashboard.data?.dpr.awaitingVerification ?? 0;

  return {
    today,
    period,
    sites,
    roster,
    dashboard,
    site: sites.data?.find((candidate) => candidate.id === siteId),
    muster: {
      total: entries.length,
      marked,
      /** The one thing the screen is loudest about. */
      unmarked: entries.length > 0 && marked === 0,
      locked: roster.data?.periodLocked ?? false,
      standardShiftHours: roster.data?.standardShiftHours ?? 0,
    },
    /** The badge on Sign-off, and the length of the list on this screen. */
    signoffCount: attendanceRowsToVerify + billsToApprove + reportsToVerify,
    waiting: { attendanceRowsToVerify, billsToApprove, draftReports, reportsToVerify },
    unsent,
    /** What this account may be shown, so the screen does not render empty panels. */
    can: { seesDashboard, signsOff },
    isLoading: roster.isLoading || (seesDashboard && dashboard.isLoading),
  };
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

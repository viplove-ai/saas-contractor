import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { saveOrQueue } from '../../offline/saveOrQueue';
import { apiClient } from '../../shared/apiClient';
import type {
  AttendanceListRow,
  BulkResult,
  MarkDraft,
  PageResponse,
  Roster,
  Site,
  VerifyAction,
  VerifyResult,
  WorkflowStatus,
} from './types';

export const attendanceKeys = {
  roster: (siteId: string, date: string) => ['attendance', 'roster', siteId, date] as const,
  /** Every cached roster, for the invalidation that follows a verification. */
  rosters: ['attendance', 'roster'] as const,
  queue: (siteId: string, from: string, to: string, status: string) =>
    ['attendance', 'queue', siteId, from, to, status] as const,
  sites: ['sites'] as const,
};

/** The queue is read one screenful at a time; 200 rows covers a large site's week. */
const QUEUE_PAGE_SIZE = 200;

export function useSites() {
  return useQuery({
    queryKey: attendanceKeys.sites,
    queryFn: async () => (await apiClient.get<Site[]>('/sites')).data,
    // Sites change about once a quarter; refetching them on every screen is waste.
    staleTime: 15 * 60_000,
  });
}

export function useRoster(siteId: string | undefined, date: string) {
  return useQuery({
    queryKey: attendanceKeys.roster(siteId ?? '', date),
    queryFn: async () =>
      (await apiClient.get<Roster>('/attendance/roster', { params: { siteId, date } })).data,
    enabled: Boolean(siteId),
  });
}

/** Either the server answered, or the batch is on the device and the queue owns it. */
export type SaveAttendanceResult =
  | { outcome: 'SENT'; result: BulkResult }
  | { outcome: 'QUEUED'; marks: number };

/**
 * Saves the marked workers. Ids are generated here, on the device, which is what makes a
 * re-send safe: the same batch arriving twice is recognised rather than duplicated.
 *
 * <p>With no connection the batch goes into the offline queue under the same ids instead of
 * failing. That is the only difference — with signal it still goes straight out, so a worker
 * somebody else already marked is still refused while the supervisor is looking at the
 * muster rather than three hours later on a screen he has left.</p>
 */
export function useSaveAttendance(siteId: string, date: string, siteLabel: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (drafts: Map<string, MarkDraft & { id: string }>) => {
      const entries = [...drafts.entries()].map(([workerId, draft]) => ({
        id: draft.id,
        workerId,
        status: draft.status,
        checkInTime: draft.checkInTime || undefined,
        checkOutTime: draft.checkOutTime || undefined,
        breakMinutes: draft.breakMinutes,
        enteredHours: draft.enteredHours,
        overtimeReason: draft.overtimeReason || undefined,
      }));
      const payload = { siteId, date, entries };
      const saved = await saveOrQueue({
        send: async () => (await apiClient.post<BulkResult>('/attendance/bulk', payload)).data,
        queue: {
          // One batch per site and day, so a supervisor who marks ten more men after lunch
          // has one queued muster carrying everything rather than two that overlap.
          clientId: `attendance:${siteId}:${date}`,
          entityType: 'ATTENDANCE',
          siteId,
          businessDate: date,
          payload,
          summary: `${entries.length} attendance mark(s) — ${siteLabel}`,
        },
      });
      return saved.outcome === 'SENT'
        ? ({ outcome: 'SENT', result: saved.result } satisfies SaveAttendanceResult)
        : ({ outcome: 'QUEUED', marks: entries.length } satisfies SaveAttendanceResult);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.roster(siteId, date) });
    },
  });
}

export function useSubmitAttendance(siteId: string, date: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () =>
      (await apiClient.post<{ submitted: number }>('/attendance/submit', { siteId, date })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.roster(siteId, date) });
    },
  });
}

/**
 * Rows at one site over a date range in a single workflow state — submitted, for the
 * sign-off queue, or verified, for correcting a day after it has been paid.
 */
export function useVerificationQueue(
  siteId: string | undefined,
  from: string,
  to: string,
  status: WorkflowStatus,
) {
  return useQuery({
    queryKey: attendanceKeys.queue(siteId ?? '', from, to, status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<AttendanceListRow>>('/attendance', {
          params: { siteId, status, from, to, size: QUEUE_PAGE_SIZE },
        })
      ).data,
    enabled: Boolean(siteId) && from <= to,
  });
}

export interface CorrectInput {
  id: string;
  enteredHours: number;
  overtimeReason?: string | undefined;
  correctionReason: string;
  version: number;
}

/**
 * Amends a row that has already been verified and paid. Separate from the mark screen's
 * save because it is a different act: the money has moved, so it carries a reason and the
 * server posts the difference to the worker's ledger.
 */
export function useCorrectAttendance() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: CorrectInput) =>
      (
        await apiClient.post<AttendanceListRow>(`/attendance/${input.id}/correct`, {
          status: 'PRESENT',
          breakMinutes: 0,
          enteredHours: input.enteredHours,
          overtimeReason: input.overtimeReason || undefined,
          correctionReason: input.correctionReason,
          version: input.version,
        })
      ).data,
    onSuccess: () => {
      // Both lists: a correction or a sign-off moves the row between them.
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'queue'] });
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.rosters });
    },
  });
}

export interface VerifyInput {
  ids: string[];
  action: VerifyAction;
  remarks?: string | undefined;
}

/**
 * Bulk sign-off. Verifying freezes each worker's wage rate and posts the earnings, so the
 * roster caches are dropped alongside the queue: rows the mark screen thought were still
 * editable no longer are.
 */
export function useVerifyAttendance() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: VerifyInput) =>
      (
        await apiClient.post<VerifyResult>('/attendance/verify', {
          ids: input.ids,
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'queue'] });
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.rosters });
    },
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  Allocation,
  EmploymentType,
  PageResponse,
  SiteDirectoryEntry,
  SkillCategory,
  WageRate,
  WageType,
  Worker,
  WorkerStatusFilter,
} from './types';

export const labourKeys = {
  workers: (siteId: string, q: string, status: WorkerStatusFilter) =>
    ['labour', 'workers', siteId, q, status] as const,
  allWorkers: ['labour', 'workers'] as const,
  allocations: (workerId: string) => ['labour', 'allocations', workerId] as const,
  wageHistory: (workerId: string) => ['labour', 'wage-rates', workerId] as const,
  directory: ['labour', 'site-directory'] as const,
  skills: ['labour', 'skill-categories'] as const,
  /** The sites the signed-in user actually works at, as opposed to the whole directory. */
  mySites: ['sites'] as const,
};

const PAGE_SIZE = 200;

export function useWorkers(siteId: string, q: string, status: WorkerStatusFilter = 'all') {
  return useQuery({
    queryKey: labourKeys.workers(siteId, q, status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Worker>>('/workers', {
          params: {
            siteId: siteId || undefined,
            q: q || undefined,
            // Left off entirely for "everybody" — the server reads an absent filter as no
            // filter, and sending active=undefined is how axios omits it.
            active: status === 'all' ? undefined : status === 'active',
            size: PAGE_SIZE,
          },
        })
      ).data,
    // Keep the previous list on screen while a new filter loads. Typing a search term
    // otherwise empties the table on every keystroke, and on a 2G site phone that is
    // several seconds of blank screen for each letter.
    placeholderData: (previous) => previous,
  });
}

/**
 * Every site in the company, for naming a transfer destination. Separate from the sites
 * query the rest of the app uses, which is narrowed to your own postings — the whole point
 * here is to reach a site you do not work at.
 */
export function useSiteDirectory() {
  return useQuery({
    queryKey: labourKeys.directory,
    queryFn: async () => (await apiClient.get<SiteDirectoryEntry[]>('/sites/directory')).data,
    staleTime: 15 * 60_000,
  });
}

export function useMySites() {
  return useQuery({
    queryKey: labourKeys.mySites,
    queryFn: async () =>
      (await apiClient.get<{ id: string; code: string; name: string }[]>('/sites')).data,
    staleTime: 15 * 60_000,
  });
}

export function useSkillCategories() {
  return useQuery({
    queryKey: labourKeys.skills,
    queryFn: async () => (await apiClient.get<SkillCategory[]>('/skill-categories')).data,
    staleTime: 15 * 60_000,
  });
}

/**
 * The whole of what the gate sends. No worker number: the server assigns it, so that two
 * supervisors cannot reach for the same one. No wage type or overtime rate either — a day's
 * wage is the basis, and the hour beyond the site's own working hours is priced from it.
 */
export interface OnboardWorkerInput {
  fullName: string;
  mobile: string;
  skillCategoryId?: string | undefined;
  siteId: string;
  /** Per day, and only sent by someone who may set pay; the server refuses it from anyone else. */
  normalRate?: number | undefined;
}

export function useOnboardWorker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: OnboardWorkerInput) =>
      (
        await apiClient.post<Worker>('/workers', {
          ...input,
          skillCategoryId: input.skillCategoryId || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: labourKeys.allWorkers });
    },
  });
}

/**
 * Correcting a worker. Every particular the server holds travels back, changed or not: the
 * update call replaces the row rather than patching it, so a field left out is a field
 * cleared. The dialog reads the unchanged ones straight off the worker it was opened on.
 */
export interface UpdateWorkerInput {
  id: string;
  fullName: string;
  mobile?: string | undefined;
  skillCategoryId?: string | undefined;
  employmentType: EmploymentType;
  labourSupplierId?: string | undefined;
  wageType: WageType;
  joiningDate?: string | undefined;
  exitDate?: string | undefined;
  aadhaarLast4?: string | undefined;
  bankAccountNo?: string | undefined;
  bankIfsc?: string | undefined;
  bankName?: string | undefined;
  active: boolean;
  /** The version read with the row. A stale one is a 409, not a silent overwrite. */
  version: number;
}

export function useUpdateWorker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: UpdateWorkerInput) =>
      (await apiClient.put<Worker>(`/workers/${id}`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: labourKeys.allWorkers });
      // His name is on the roster the marking screen draws, and it is cached offline.
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'roster'] });
    },
  });
}

/**
 * Takes a man off the books. The server refuses one who has been marked, paid or advanced
 * anything and says what is holding him, which is the answer that changes the engineer's
 * mind — so the refusal is shown in the dialog rather than swallowed.
 */
export function useDeleteWorker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { workerId: string; reason: string }) =>
      (
        await apiClient.delete<Worker>(`/workers/${input.workerId}`, {
          data: { reason: input.reason },
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: labourKeys.allWorkers });
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'roster'] });
    },
  });
}

export function useAllocations(workerId: string | undefined) {
  return useQuery({
    queryKey: labourKeys.allocations(workerId ?? ''),
    queryFn: async () =>
      (await apiClient.get<Allocation[]>(`/workers/${workerId}/allocations`)).data,
    enabled: Boolean(workerId),
  });
}

/**
 * Hands the man over to another site. The receiving supervisor needs nothing to accept
 * him: from the effective date he is simply on their roster, which is what the site
 * assignment on the server already means.
 */
export function useTransferWorker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { workerId: string; siteId: string; effectiveFrom: string }) =>
      (
        await apiClient.post<Allocation>(`/workers/${input.workerId}/allocations`, {
          siteId: input.siteId,
          effectiveFrom: input.effectiveFrom,
        })
      ).data,
    onSuccess: (_result, input) => {
      // He leaves this user's roster on the same date, so both lists are now wrong.
      void queryClient.invalidateQueries({ queryKey: labourKeys.allWorkers });
      void queryClient.invalidateQueries({ queryKey: labourKeys.allocations(input.workerId) });
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'roster'] });
    },
  });
}

export function useWageHistory(workerId: string | undefined) {
  return useQuery({
    queryKey: labourKeys.wageHistory(workerId ?? ''),
    queryFn: async () =>
      (await apiClient.get<WageRate[]>(`/workers/${workerId}/wage-rates`)).data,
    enabled: Boolean(workerId),
  });
}

/**
 * What the revision sends. No overtime rate unless one was typed: left out, the server
 * derives it from the day's wage and the shift length of the site he stands on that day,
 * exactly as it does when he is taken on.
 */
export interface ReviseWageInput {
  workerId: string;
  normalRate: number;
  overtimeRate?: number | undefined;
  effectiveFrom: string;
  remarks?: string | undefined;
}

/**
 * A revision, never an edit: the server closes the rate in force the day before the new one
 * begins and opens the new one. Days already verified keep the rate frozen onto them.
 */
export function useReviseWage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ workerId, ...body }: ReviseWageInput) =>
      (await apiClient.post<WageRate>(`/workers/${workerId}/wage-rates`, body)).data,
    onSuccess: (_result, input) => {
      void queryClient.invalidateQueries({ queryKey: labourKeys.allWorkers });
      void queryClient.invalidateQueries({ queryKey: labourKeys.wageHistory(input.workerId) });
      // The roster carries each man's rate in force, and it is cached offline.
      void queryClient.invalidateQueries({ queryKey: ['attendance', 'roster'] });
    },
  });
}

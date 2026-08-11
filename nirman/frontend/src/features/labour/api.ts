import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  Allocation,
  PageResponse,
  SiteDirectoryEntry,
  SkillCategory,
  Worker,
} from './types';

export const labourKeys = {
  workers: (siteId: string, q: string) => ['labour', 'workers', siteId, q] as const,
  allWorkers: ['labour', 'workers'] as const,
  allocations: (workerId: string) => ['labour', 'allocations', workerId] as const,
  directory: ['labour', 'site-directory'] as const,
  skills: ['labour', 'skill-categories'] as const,
  /** The sites the signed-in user actually works at, as opposed to the whole directory. */
  mySites: ['sites'] as const,
};

const PAGE_SIZE = 200;

export function useWorkers(siteId: string, q: string) {
  return useQuery({
    queryKey: labourKeys.workers(siteId, q),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Worker>>('/workers', {
          params: { siteId: siteId || undefined, q: q || undefined, size: PAGE_SIZE },
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

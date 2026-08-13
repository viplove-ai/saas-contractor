import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type { GeneratePlanInput, Plan, WorkTypeProfile } from './types';

export const planningKeys = {
  profiles: ['planning', 'work-type-profiles'] as const,
  plan: (projectId: string) => ['planning', 'plan', projectId] as const,
};

export function useWorkTypeProfiles() {
  return useQuery({
    queryKey: planningKeys.profiles,
    queryFn: async () =>
      (await apiClient.get<WorkTypeProfile[]>('/planning/norms/work-type-profiles')).data,
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Runs the engine and keeps nothing.
 *
 * A mutation rather than a query because it is a POST with a body the user is changing — and
 * because caching a scenario would defeat the point of being able to try another one.
 */
export function useGeneratePlan(projectId: string | undefined) {
  return useMutation({
    mutationFn: async (input: GeneratePlanInput) =>
      (await apiClient.post<Plan>(`/planning/projects/${projectId}/preview`, input)).data,
  });
}

/** Keeps the generated plan as a draft, which is what a baseline is then made from. */
export function useSavePlan(projectId: string | undefined) {
  return useMutation({
    mutationFn: async (input: GeneratePlanInput) =>
      (await apiClient.post<Plan>(`/planning/projects/${projectId}/plans`, input)).data,
  });
}

/** Freezes it. From here the plan is superseded rather than edited. */
export function useBaselinePlan(projectId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (planId: string) =>
      (await apiClient.post<Plan>(`/planning/plans/${planId}/baseline`, {})).data,
    onSuccess: () => {
      if (projectId) {
        void queryClient.invalidateQueries({ queryKey: planningKeys.plan(projectId) });
      }
    },
  });
}

/** The live baseline, if the project has one. 404 simply means it has not been planned yet. */
export function useProjectPlan(projectId: string | undefined) {
  return useQuery({
    queryKey: planningKeys.plan(projectId ?? ''),
    queryFn: async () => (await apiClient.get<Plan>(`/planning/projects/${projectId}/plan`)).data,
    enabled: !!projectId,
    retry: false,
  });
}

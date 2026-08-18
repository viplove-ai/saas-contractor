import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  Security,
  SecurityInstrument,
  SecurityProposal,
  SecurityType,
  TreasuryDashboard,
} from './types';

export const treasuryKeys = {
  dashboard: (asOf: string) => ['treasury', 'dashboard', asOf] as const,
  forProject: (projectId: string) => ['treasury', 'project', projectId] as const,
  proposal: (projectId: string) => ['treasury', 'proposal', projectId] as const,
};

/**
 * Never cached by the service worker and never served stale — a deposit figure read off an
 * old cache is the one number on this app somebody would ring a bank about.
 */
export function useTreasuryDashboard(asOf: string) {
  return useQuery({
    queryKey: treasuryKeys.dashboard(asOf),
    queryFn: async () =>
      (await apiClient.get<TreasuryDashboard>('/treasury/dashboard', { params: { asOf } })).data,
  });
}

export function useProjectSecurities(projectId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: treasuryKeys.forProject(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<Security[]>(`/projects/${projectId}/securities`)).data,
    enabled: Boolean(projectId) && enabled,
  });
}

/**
 * @param enabled pass false where the caller does not hold {@code security:read} — firing it
 *                anyway would answer a project page with a 403 nobody can act on.
 */
export function useSecurityProposal(projectId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: treasuryKeys.proposal(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<SecurityProposal[]>(`/projects/${projectId}/securities/proposal`)).data,
    enabled: Boolean(projectId) && enabled,
  });
}

export interface CreateSecurityInput {
  projectId: string;
  securityType: SecurityType;
  instrument: SecurityInstrument;
  amount: number;
  basis?: string | undefined;
  expectedReleaseOn?: string | undefined;
  notes?: string | undefined;
}

export interface UpdateSecurityInput {
  id: string;
  instrument: SecurityInstrument;
  amount: number;
  basis?: string | undefined;
  referenceNo?: string | undefined;
  bankName?: string | undefined;
  branch?: string | undefined;
  lodgedOn?: string | undefined;
  maturityOn?: string | undefined;
  expectedReleaseOn?: string | undefined;
  notes?: string | undefined;
  version: number;
}

/**
 * Every write invalidates both the project's register and the company dashboard, because a
 * lodgement changes a company total as surely as it changes a row — and an office reading a
 * total that has not moved after recording a fifteen-lakh guarantee will record it twice.
 */
function useSecurityMutation<TInput>(
  send: (input: TInput) => Promise<Security>,
  projectIdOf: (input: TInput, result: Security) => string,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSuccess: (result, input) => {
      void queryClient.invalidateQueries({
        queryKey: treasuryKeys.forProject(projectIdOf(input, result)),
      });
      void queryClient.invalidateQueries({ queryKey: treasuryKeys.proposal(result.projectId) });
      void queryClient.invalidateQueries({ queryKey: ['treasury', 'dashboard'] });
    },
  });
}

export function useCreateSecurity() {
  return useSecurityMutation(
    async (input: CreateSecurityInput) =>
      (await apiClient.post<Security>('/securities', input)).data,
    (input) => input.projectId,
  );
}

export function useUpdateSecurity() {
  return useSecurityMutation(
    async (input: UpdateSecurityInput) =>
      (await apiClient.put<Security>(`/securities/${input.id}`, input)).data,
    (_input, result) => result.projectId,
  );
}

export interface LodgeInput {
  id: string;
  lodgedOn: string;
  referenceNo?: string | undefined;
  bankName?: string | undefined;
  branch?: string | undefined;
  maturityOn?: string | undefined;
  expectedReleaseOn?: string | undefined;
  version: number;
}

export function useLodgeSecurity() {
  return useSecurityMutation(
    async (input: LodgeInput) =>
      (await apiClient.post<Security>(`/securities/${input.id}/lodge`, input)).data,
    (_input, result) => result.projectId,
  );
}

export function useRecordRetained() {
  return useSecurityMutation(
    async (input: { id: string; retainedToDate: number; asOf: string; version: number }) =>
      (await apiClient.post<Security>(`/securities/${input.id}/retained`, input)).data,
    (_input, result) => result.projectId,
  );
}

export function useReleaseSecurity() {
  return useSecurityMutation(
    async (input: {
      id: string;
      releasedOn: string;
      reference?: string | undefined;
      version: number;
    }) => (await apiClient.post<Security>(`/securities/${input.id}/release`, input)).data,
    (_input, result) => result.projectId,
  );
}

export function useRedeploySecurity() {
  return useSecurityMutation(
    async (input: { id: string; toProjectId: string; version: number }) =>
      (await apiClient.post<Security>(`/securities/${input.id}/redeploy`, input)).data,
    (_input, result) => result.projectId,
  );
}

export function useForfeitSecurity() {
  return useSecurityMutation(
    async (input: { id: string; reason: string; version: number }) =>
      (await apiClient.post<Security>(`/securities/${input.id}/forfeit`, input)).data,
    (_input, result) => result.projectId,
  );
}

export function useDeleteSecurity() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; projectId: string }) => {
      await apiClient.delete(`/securities/${id}`);
    },
    onSuccess: (_result, input) => {
      void queryClient.invalidateQueries({ queryKey: treasuryKeys.forProject(input.projectId) });
      void queryClient.invalidateQueries({ queryKey: treasuryKeys.proposal(input.projectId) });
      void queryClient.invalidateQueries({ queryKey: ['treasury', 'dashboard'] });
    },
  });
}

/**
 * The contracts a released deposit could be named onto.
 *
 * <p>Fetched here rather than borrowed from the admin feature: features must not import from
 * one another, and this needs three fields of what that hook returns whole.</p>
 */
export function useProjectDirectory(enabled = true) {
  return useQuery({
    enabled,
    queryKey: ['treasury', 'projects'],
    queryFn: async () => {
      const page = await apiClient.get<{
        content: { id: string; code: string; name: string }[];
      }>('/projects', { params: { size: 100 } });
      return page.data.content.map((project) => ({
        id: project.id,
        code: project.code,
        name: project.name,
      }));
    },
    staleTime: 5 * 60_000,
  });
}

/** Today, as the ISO date the API and every date input both want. */
export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

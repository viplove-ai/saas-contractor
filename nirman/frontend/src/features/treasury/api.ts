import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { compressPhoto } from '../../offline/uploads';
import { apiClient } from '../../shared/apiClient';
import type {
  BankDeposit,
  DepositRegister,
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
  deposits: () => ['treasury', 'deposits'] as const,
  /** One signed link per attachment, shared by the thumbnail and the full view drawn from it. */
  attachmentUrl: (attachmentId: string) => ['attachments', attachmentId, 'url'] as const,
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
  /**
   * The certificate out of the FDR register being pledged, where the deposit is one. Leaving it
   * unset is the older behaviour and still right for cash, a bank guarantee, or a certificate
   * nobody has entered into the register yet — the bank details typed alongside are then all
   * there is.
   */
  bankDepositId?: string | undefined;
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

// ------------------------------------------------------------------ the FDR register

/**
 * Every fixed deposit the company holds, with what each is pledged against.
 *
 * <p>Uncached like every other treasury read, and for the sharper version of the same reason: a
 * stale register is what somebody consults before telling a bank to close a certificate.</p>
 */
export function useDepositRegister(enabled = true) {
  return useQuery({
    queryKey: treasuryKeys.deposits(),
    queryFn: async () => (await apiClient.get<DepositRegister>('/bank-deposits')).data,
    enabled,
  });
}

export interface DepositInput {
  depositNumber: string;
  bankName: string;
  branch?: string | undefined;
  amount: number;
  issuedOn: string;
  maturityOn?: string | undefined;
  interestRate?: number | undefined;
  notes?: string | undefined;
}

/**
 * Every write refreshes the register and the company dashboard both. A certificate closed or
 * pledged moves a company total as surely as it moves its own row, and an office reading a
 * total that has not budged after entering a five-lakh FDR will enter it twice.
 */
function useDepositMutation<TInput>(send: (input: TInput) => Promise<BankDeposit>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: treasuryKeys.deposits() });
      void queryClient.invalidateQueries({ queryKey: ['treasury', 'dashboard'] });
    },
  });
}

export function useCreateDeposit() {
  return useDepositMutation(
    async (input: DepositInput) =>
      (await apiClient.post<BankDeposit>('/bank-deposits', input)).data,
  );
}

export function useUpdateDeposit() {
  return useDepositMutation(
    async (input: DepositInput & { id: string; version: number }) =>
      (await apiClient.put<BankDeposit>(`/bank-deposits/${input.id}`, input)).data,
  );
}

export function useCloseDeposit() {
  return useDepositMutation(
    async (input: { id: string; closedOn: string; reason?: string | undefined; version: number }) =>
      (await apiClient.post<BankDeposit>(`/bank-deposits/${input.id}/close`, input)).data,
  );
}

export function useReopenDeposit() {
  return useDepositMutation(
    async (input: { id: string; version: number }) =>
      (await apiClient.post<BankDeposit>(`/bank-deposits/${input.id}/reopen`, input)).data,
  );
}

/**
 * The photograph second, and only once the certificate exists — the order every other picture in
 * the app takes. An attachment with no owner is a stray file somebody can clean up; a register
 * row pointing at a file that was never stored is a broken picture nobody can fix.
 */
export function useAddDepositPhoto() {
  return useDepositMutation(
    async (input: { depositId: string; file: File; caption?: string | undefined }) => {
      const photo = await compressPhoto(input.file);
      const form = new FormData();
      form.append('file', photo.blob, photo.fileName);
      const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
        params: { ownerEntityType: 'BANK_DEPOSIT', kind: 'PHOTO' },
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return (
        await apiClient.post<BankDeposit>(`/bank-deposits/${input.depositId}/photos`, {
          attachmentId: uploaded.data.id,
          caption: input.caption,
        })
      ).data;
    },
  );
}

export function useRemoveDepositPhoto() {
  return useDepositMutation(
    async (input: { depositId: string; attachmentId: string }) =>
      (
        await apiClient.delete<BankDeposit>(
          `/bank-deposits/${input.depositId}/photos/${input.attachmentId}`,
        )
      ).data,
  );
}

/**
 * A signed link to one stored photograph.
 *
 * <p>Its own hook here rather than the inventory module's: features do not import from one
 * another, and a certificate is not a fact about the store.</p>
 */
export function useAttachmentUrl(attachmentId: string | undefined) {
  return useQuery({
    queryKey: treasuryKeys.attachmentUrl(attachmentId ?? ''),
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60 * 1000,
  });
}

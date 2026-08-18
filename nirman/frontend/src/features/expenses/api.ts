import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { drain } from '../../offline/queue';
import { saveOrQueue } from '../../offline/saveOrQueue';
import { queuePhoto } from '../../offline/uploads';
import { apiClient } from '../../shared/apiClient';
import type {
  AllocationSummary,
  Approval,
  ApprovalAction,
  CostAllocation,
  DuplicateCandidate,
  DuplicateExpenseError,
  Expense,
  ExpenseCategory,
  ExpenseWorkflow,
  PageResponse,
  Payment,
  PaymentStatus,
  Site,
  Vendor,
  VendorBalance,
} from './types';

export const expenseKeys = {
  sites: ['sites'] as const,
  categories: ['expense-categories'] as const,
  vendors: ['vendors'] as const,
  list: (siteId: string, status: string) => ['expenses', siteId, status] as const,
  all: ['expenses'] as const,
  register: ['expenses', 'register'] as const,
  pending: ['approvals', 'pending'] as const,
  payments: (expenseId: string) => ['payments', expenseId] as const,
  vendorBalances: ['vendors', 'balances'] as const,
  attachmentUrl: (attachmentId: string) => ['attachments', attachmentId, 'url'] as const,
};

const REFERENCE_STALE_TIME = 15 * 60_000;

export function useSites() {
  return useQuery({
    queryKey: expenseKeys.sites,
    queryFn: async () => (await apiClient.get<Site[]>('/sites')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * The heads an expense can be booked against: the active leaves, not the headings above them.
 *
 * <p>Leaf by structure rather than by "has a parent". The screen used to ask for a parent id
 * and an organisation whose taxonomy is one flat level got a picker with nothing in it —
 * every head it had was thrown away for not being underneath another one.</p>
 */
export function useExpenseCategories() {
  return useQuery({
    queryKey: expenseKeys.categories,
    queryFn: async () => {
      const active = (await apiClient.get<ExpenseCategory[]>('/expense-categories')).data.filter(
        (category) => category.active,
      );
      const parents = new Set(active.map((category) => category.parentId).filter(Boolean));
      return active.filter((category) => !parents.has(category.id));
    },
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * A head named at the site, for spending the taxonomy has never heard of.
 *
 * <p>The row it creates is a phrase off a bill with neither cost flag set, marked
 * provisional so the office can find it. A name the organisation already holds comes back as
 * the head it already holds, so "site cleaning" typed twice does not become two lines of the
 * same report.</p>
 */
export function useNameExpenseCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string }) =>
      (await apiClient.post<ExpenseCategory>('/expense-categories/field', input)).data,
    onSuccess: (created) => {
      /*
        Into the list before the refetch lands, not after. The screen selects the head it
        just opened, and a picker holding a value none of its options carry is a picker that
        has quietly emptied itself — for however long the round trip takes, which on a site
        handset is not a moment.
      */
      queryClient.setQueryData<ExpenseCategory[]>(expenseKeys.categories, (current) =>
        current && !current.some((category) => category.id === created.id)
          ? [...current, created]
          : current,
      );
      void queryClient.invalidateQueries({ queryKey: expenseKeys.categories });
    },
  });
}

export function useVendors() {
  return useQuery({
    queryKey: expenseKeys.vendors,
    queryFn: async () =>
      (await apiClient.get<PageResponse<Vendor>>('/vendors', { params: { size: 200 } })).data
        .content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useExpenses(siteId: string | undefined, status: ExpenseWorkflow | '') {
  return useQuery({
    queryKey: expenseKeys.list(siteId ?? '', status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Expense>>('/expenses', {
          params: { siteId, status: status || undefined, size: 100 },
        })
      ).data,
    enabled: Boolean(siteId),
  });
}

export interface ExpenseInput {
  id: string;
  siteId: string;
  expenseDate: string;
  categoryId: string;
  vendorId?: string | undefined;
  description: string;
  billNumber?: string | undefined;
  amountBeforeTax: number;
  gstPercent: number;
  paymentMode?: string | undefined;
  noBillReason?: string | undefined;
  duplicateOverrideReason?: string | undefined;
}

/** Either the server booked it, or it is on the device under the id it will keep. */
export type CreateExpenseResult =
  | { outcome: 'SENT'; expense: Expense }
  | { outcome: 'QUEUED'; id: string; photoQueued: boolean };

/**
 * Books an expense. The id is generated here, on the device, so a bill photographed and
 * typed at site with no signal and synced three times is one expense.
 *
 * <p>{@link force} re-sends after a duplicate warning; the server then requires a reason,
 * which is why the screen collects one before setting it.
 *
 * <p>With no connection the expense goes into the offline queue instead of failing, and the
 * photograph — already compressed by the caller — is queued behind it. It is queued behind
 * rather than with it because a bill number and a total are what the office needs on Monday;
 * the photograph is the evidence for it and is fifty times the size.</p>
 */
export function useCreateExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      input,
      force,
      siteLabel,
      photo,
    }: {
      input: ExpenseInput;
      force?: boolean;
      siteLabel: string;
      photo?: File | undefined;
    }): Promise<CreateExpenseResult> => {
      const saved = await saveOrQueue({
        send: async () =>
          (
            await apiClient.post<Expense>('/expenses', input, {
              params: force ? { force: true } : undefined,
            })
          ).data,
        queue: {
          clientId: input.id,
          entityType: 'EXPENSE',
          siteId: input.siteId,
          businessDate: input.expenseDate,
          payload: input,
          summary: `${input.description} — ${siteLabel}`,
        },
      });
      if (photo) {
        await queuePhoto({
          clientId: input.id,
          entityType: 'EXPENSE',
          siteId: input.siteId,
          file: photo,
        });
      }
      return saved.outcome === 'SENT'
        ? { outcome: 'SENT', expense: saved.result }
        : { outcome: 'QUEUED', id: input.id, photoQueued: Boolean(photo) };
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      // The photograph is queued either way, so the drain is what actually sends it when
      // there was a connection all along. Fire and forget: the expense is already booked and
      // a failed upload is the queue's problem from here.
      void drain();
    },
  });
}

/**
 * The candidates behind a 409, or null for any other failure.
 *
 * <p>A duplicate warning is only useful with the rows attached — "duplicate expense" alone
 * sends somebody hunting through a month of paper for something the server already had.
 */
export function duplicateCandidates(error: unknown): DuplicateCandidate[] | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 409) {
    return null;
  }
  const body = error.response.data as DuplicateExpenseError | undefined;
  return body?.candidates ?? null;
}

export interface ExpenseEdit {
  id: string;
  expenseDate: string;
  categoryId: string;
  description: string;
  billNumber?: string | undefined;
  amountBeforeTax: number;
  gstPercent: number;
  noBillReason?: string | undefined;
  version: number;
}

/**
 * Correcting an expense the office sent back, or a draft not yet sent.
 *
 * <p>Not queued when there is no signal, unlike booking one. A correction is a reply to
 * something the office said about a row that already exists on the server, and the row may
 * have moved since — approved by somebody else, or returned a second time. Queuing the reply
 * would have it apply days later to a record that no longer says what was being answered,
 * which is exactly the case {@code version} exists to refuse. So it waits for a connection,
 * and the screen says so.</p>
 */
export function useUpdateExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: ExpenseEdit) =>
      (await apiClient.put<Expense>(`/expenses/${id}`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
    },
  });
}

/**
 * A bill photographed onto an expense that already exists — the correction path's half of
 * what {@link useCreateExpense} does in one go for a new one.
 *
 * <p>Two calls, file first, exactly as the offline drain does it: an attachment with no owner
 * is a draft upload its uploader may still delete, while a link to a file that does not exist
 * is a bill somebody has to approve without being able to see it. "There is no bill" is one
 * of the two things an expense is sent back for, so this is most of why the screen lets a
 * returned row be corrected at all.</p>
 */
export function useAttachBill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      expenseId,
      siteId,
      file,
    }: {
      expenseId: string;
      siteId: string;
      file: File;
    }) => {
      const form = new FormData();
      form.append('file', file, file.name);
      const created = await apiClient.post<{ id: string }>('/attachments', form, {
        params: { ownerEntityType: 'EXPENSE', kind: 'BILL', siteId },
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return (
        await apiClient.post<Expense>(`/expenses/${expenseId}/attachments`, {
          attachmentId: created.data.id,
          docType: 'BILL',
        })
      ).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
    },
  });
}

export function useSubmitExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<Expense>(`/expenses/${id}/submit`)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.pending });
    },
  });
}

/**
 * The decision, with whose cost it is attached to it.
 *
 * <p>One call, because approving the money and saying whose money it was are one act by one
 * person at one moment. Two would leave a window in which an approved expense is charged to
 * nobody, and a second screen for the approver who never came back.</p>
 *
 * <p>The allocation is left off a rejection or a return: a refusal decides nothing about a
 * cost that is not being incurred, and the row keeps its head's proposal for when it comes
 * back round.</p>
 */
export function useDecideExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      action: ApprovalAction;
      remarks?: string | undefined;
      allocation?: CostAllocation | undefined;
      siteShare?: number | undefined;
      allocationNote?: string | undefined;
    }) =>
      (
        await apiClient.post<Expense>(`/expenses/${input.id}/approve`, {
          action: input.action,
          remarks: input.remarks || undefined,
          ...(input.action === 'APPROVE' && input.allocation
            ? {
                allocation: input.allocation,
                ...(input.allocation === 'SPLIT' ? { siteShare: input.siteShare } : {}),
                ...(input.allocationNote ? { allocationNote: input.allocationNote } : {}),
              }
            : {}),
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.pending });
    },
  });
}

/**
 * Whose cost an approved expense was, re-decided by the office.
 *
 * <p>The month read afterwards: the approver had the bill in front of him and was wrong about
 * it, and the accountant reading the register can see that the diesel was the office car's.
 * Refused once the site closes — those figures have gone to the department.</p>
 */
export function useAllocateExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      allocation: CostAllocation;
      siteShare?: number | undefined;
      note?: string | undefined;
    }) =>
      (
        await apiClient.put<Expense>(`/expenses/${input.id}/allocation`, {
          allocation: input.allocation,
          siteShare: input.allocation === 'SPLIT' ? input.siteShare : undefined,
          note: input.note || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.register });
    },
  });
}

/**
 * Re-opening an approved expense the author needs to correct.
 *
 * <p>Not an edit behind a signature: the row keeps its number, its approval is cancelled, and
 * it goes back through the same chain as a first submission. Not queued offline either, for
 * the reason {@link useUpdateExpense} is not — it is a reply to a record the server already
 * holds, and applying it days later to a row that has moved is exactly what {@code version}
 * refuses.</p>
 */
export function useReviseExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: ExpenseRevision) =>
      (await apiClient.post<Expense>(`/expenses/${id}/revise`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.pending });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.register });
    },
  });
}

export interface ExpenseRevision extends ExpenseEdit {
  /** What was wrong with a figure somebody had already approved. */
  reason: string;
}

/**
 * The register: every payment record the caller may see, narrowed by the filters above it.
 *
 * <p>Its own query key rather than {@link useExpenses}': this screen filters on things no
 * other screen does, and sharing a key would have the approvals screen serving a page that
 * had been narrowed to company costs in March.</p>
 */
export function usePaymentRegister(filters: RegisterFilters) {
  return useQuery({
    queryKey: [...expenseKeys.register, filters],
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Expense>>('/expenses', {
          params: { ...cleaned(filters), size: 100 },
        })
      ).data,
  });
}

/** What the same filter adds up to. Server-side, over every row rather than the page shown. */
export function useRegisterSummary(filters: RegisterFilters) {
  const { siteId, allocation, from, to } = filters;
  return useQuery({
    queryKey: [...expenseKeys.register, 'summary', { siteId, allocation, from, to }],
    queryFn: async () =>
      (
        await apiClient.get<AllocationSummary>('/expenses/summary', {
          params: cleaned({ siteId, allocation, from, to }),
        })
      ).data,
  });
}

export interface RegisterFilters {
  siteId?: string | undefined;
  status?: ExpenseWorkflow | undefined;
  allocation?: CostAllocation | undefined;
  paymentStatus?: PaymentStatus | undefined;
  categoryId?: string | undefined;
  vendorId?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
}

/** An empty filter is not a filter. Axios would otherwise send `status=` and match nothing. */
function cleaned(filters: object): Record<string, string> {
  return Object.fromEntries(
    Object.entries(filters).filter(([, value]) => Boolean(value)),
  ) as Record<string, string>;
}

export function useVoidExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; reason: string }) =>
      (await apiClient.post<Expense>(`/expenses/${input.id}/void`, { reason: input.reason })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.pending });
    },
  });
}

/** Everything waiting on the caller, whatever module raised it. */
export function usePendingApprovals(entityType?: string) {
  return useQuery({
    queryKey: [...expenseKeys.pending, entityType ?? 'all'],
    queryFn: async () =>
      (await apiClient.get<Approval[]>('/approvals/pending', { params: { entityType } })).data,
  });
}

export function usePayments(expenseId: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.payments(expenseId ?? ''),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Payment>>('/payments', {
          params: { expenseId, size: 50 },
        })
      ).data,
    enabled: Boolean(expenseId),
  });
}

export interface PaymentInput {
  expenseId: string;
  paymentDate: string;
  amount: number;
  paymentMode: string;
  referenceNumber?: string | undefined;
  remarks?: string | undefined;
}

export function useRecordPayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: PaymentInput) =>
      (await apiClient.post<Payment>('/payments', input)).data,
    onSuccess: () => {
      // A payment moves the expense's paid and payable figures, so both caches go.
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['payments'] });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.vendorBalances });
    },
  });
}

export function useVendorBalances() {
  return useQuery({
    queryKey: expenseKeys.vendorBalances,
    queryFn: async () => (await apiClient.get<VendorBalance[]>('/vendors/balances')).data,
  });
}

/**
 * A short-lived link to a bill already uploaded.
 *
 * <p>Asked for per attachment, when something is about to draw it, rather than carried on the
 * expense rows: the server signs it fresh and re-checks the caller's sites on the way, and it
 * dies in ten minutes — so a queue of forty bills would otherwise mint forty links for the
 * two an approver actually opens.</p>
 *
 * <p>Cached for five minutes, comfortably inside the link's own life, so opening the same
 * bill twice while deciding on it is one call.</p>
 */
export function useAttachmentUrl(attachmentId: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.attachmentUrl(attachmentId ?? ''),
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60_000,
    gcTime: 5 * 60_000,
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { drain } from '../../offline/queue';
import { saveOrQueue } from '../../offline/saveOrQueue';
import { queuePhoto } from '../../offline/uploads';
import { apiClient } from '../../shared/apiClient';
import type {
  Approval,
  ApprovalAction,
  DuplicateCandidate,
  DuplicateExpenseError,
  Expense,
  ExpenseCategory,
  ExpenseWorkflow,
  PageResponse,
  Payment,
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
  pending: ['approvals', 'pending'] as const,
  payments: (expenseId: string) => ['payments', expenseId] as const,
  vendorBalances: ['vendors', 'balances'] as const,
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

export function useDecideExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      action: ApprovalAction;
      remarks?: string | undefined;
    }) =>
      (
        await apiClient.post<Expense>(`/expenses/${input.id}/approve`, {
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
      void queryClient.invalidateQueries({ queryKey: expenseKeys.pending });
    },
  });
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

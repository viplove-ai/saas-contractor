import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
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

export function useExpenseCategories() {
  return useQuery({
    queryKey: expenseKeys.categories,
    queryFn: async () =>
      (await apiClient.get<ExpenseCategory[]>('/expense-categories')).data.filter((c) => c.active),
    staleTime: REFERENCE_STALE_TIME,
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

/**
 * Books an expense. The id is generated here, on the device, so a bill photographed and
 * typed at site with no signal and synced three times is one expense.
 *
 * <p>{@link force} re-sends after a duplicate warning; the server then requires a reason,
 * which is why the screen collects one before setting it.
 */
export function useCreateExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ input, force }: { input: ExpenseInput; force?: boolean }) =>
      (
        await apiClient.post<Expense>('/expenses', input, {
          params: force ? { force: true } : undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: expenseKeys.all });
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

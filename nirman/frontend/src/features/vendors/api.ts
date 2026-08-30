import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  PageResponse,
  SupplierEngagement,
  Vendor,
  VendorAccount,
  VendorPayment,
  VendorPurchase,
  VendorType,
} from './types';

export const vendorKeys = {
  all: ['vendors'] as const,
  list: (q: string, type: string) => ['vendors', 'list', q, type] as const,
  account: (id: string) => ['vendors', 'account', id] as const,
  purchases: (id: string) => ['vendors', 'purchases', id] as const,
  labour: (id: string) => ['vendors', 'labour', id] as const,
  payments: (id: string) => ['vendors', 'payments', id] as const,
};

/** A contractor's supplier list is short enough to hold on one page. */
const PAGE_SIZE = 100;

export function useVendors(q = '', type = '') {
  return useQuery({
    queryKey: vendorKeys.list(q, type),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Vendor>>('/vendors', {
          params: { q: q || undefined, type: type || undefined, size: PAGE_SIZE },
        })
      ).data.content,
  });
}

export interface VendorInput {
  name: string;
  vendorType: VendorType;
  contactPerson?: string | undefined;
  mobile?: string | undefined;
  email?: string | undefined;
  address?: string | undefined;
  gstin?: string | undefined;
  pan?: string | undefined;
  bankAccountNo?: string | undefined;
  bankIfsc?: string | undefined;
  creditDays: number;
  openingBalance?: number | undefined;
}

export function useCreateVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: VendorInput) =>
      (await apiClient.post<Vendor>('/vendors', blankToUndefined(input))).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: vendorKeys.all });
    },
  });
}

export function useUpdateVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (
      input: Omit<VendorInput, 'code' | 'openingBalance'> & {
        id: string;
        active: boolean;
        version: number;
      },
    ) =>
      // No code and no opening balance: a supplier's code is permanent, and his opening
      // balance is what he was owed on day one — editable once by migration, never here.
      (await apiClient.put<Vendor>(`/vendors/${input.id}`, blankToUndefined(input))).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: vendorKeys.all });
    },
  });
}

/**
 * What the field may say about a supplier: who he is, what he supplies, how to reach him.
 *
 * <p>Deliberately not a subset of {@link VendorInput} taken at the call site — the point of
 * the field endpoints is that the tax numbers, the bank account and the credit period are
 * not reachable from them at all, and a type that merely omits them says so.</p>
 */
export interface FieldVendorInput {
  name: string;
  vendorType: VendorType;
  contactPerson?: string | undefined;
  mobile?: string | undefined;
  email?: string | undefined;
  address?: string | undefined;
}

/** Naming the firm whose lorry is at the gate. The row comes back marked provisional. */
export function useNameVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: FieldVendorInput) =>
      (await apiClient.post<Vendor>('/vendors/field', blankToUndefined(input))).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: vendorKeys.all });
    },
  });
}

/** The same fields a day later. Nothing carrying a number, and not the active flag. */
export function useCorrectFieldVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      ...body
    }: FieldVendorInput & { id: string; version: number }) =>
      (await apiClient.put<Vendor>(`/vendors/${id}/field`, blankToUndefined(body))).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: vendorKeys.all });
    },
  });
}

export function useVendorAccount(vendorId: string | undefined) {
  return useQuery({
    queryKey: vendorKeys.account(vendorId ?? ''),
    queryFn: async () =>
      (await apiClient.get<VendorAccount>(`/vendors/${vendorId}/account`)).data,
    enabled: Boolean(vendorId),
  });
}

export function useVendorPurchases(vendorId: string | undefined) {
  return useQuery({
    queryKey: vendorKeys.purchases(vendorId ?? ''),
    queryFn: async () =>
      (await apiClient.get<VendorPurchase[]>(`/vendors/${vendorId}/purchases`)).data,
    enabled: Boolean(vendorId),
  });
}

/** Where his men have worked. Empty for a dealer who only sends material. */
export function useVendorLabour(vendorId: string | undefined) {
  return useQuery({
    queryKey: vendorKeys.labour(vendorId ?? ''),
    queryFn: async () =>
      (await apiClient.get<SupplierEngagement[]>(`/vendors/${vendorId}/labour`)).data,
    enabled: Boolean(vendorId),
  });
}

export function useVendorPayments(vendorId: string | undefined) {
  return useQuery({
    queryKey: vendorKeys.payments(vendorId ?? ''),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<VendorPayment>>('/payments', {
          params: { vendorId, size: PAGE_SIZE },
        })
      ).data.content,
    enabled: Boolean(vendorId),
  });
}

export interface VendorAdvanceInput {
  vendorId: string;
  paymentDate: string;
  amount: number;
  paymentMode: string;
  referenceNumber?: string | undefined;
  remarks: string;
}

/**
 * Cash to a supplier before any bill of his exists — how a lorry of steel gets loaded.
 * It lands against him rather than against a bill, and is set off as his bills arrive.
 */
export function useRecordVendorAdvance() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ vendorId, ...body }: VendorAdvanceInput) =>
      (
        await apiClient.post<VendorPayment>(`/vendors/${vendorId}/advances`, {
          ...body,
          referenceNumber: body.referenceNumber || undefined,
        })
      ).data,
    onSuccess: (_data, input) => {
      // The account and the payment list both moved; neither is cached anywhere else.
      void queryClient.invalidateQueries({ queryKey: vendorKeys.account(input.vendorId) });
      void queryClient.invalidateQueries({ queryKey: vendorKeys.payments(input.vendorId) });
    },
  });
}

/**
 * An empty box means "not given", which the server reads as null. Sending "" instead would
 * store a blank GSTIN as a GSTIN, and the difference matters the day somebody filters on it.
 */
function blankToUndefined<T extends object>(input: T): T {
  return Object.fromEntries(
    Object.entries(input).map(([key, value]) => [key, value === '' ? undefined : value]),
  ) as T;
}

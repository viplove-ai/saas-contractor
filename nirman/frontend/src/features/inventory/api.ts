import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  BoqItem,
  Issue,
  LedgerRow,
  Material,
  PageResponse,
  Receipt,
  Site,
  StockPosition,
  Store,
  Unit,
} from './types';

export const inventoryKeys = {
  sites: ['sites'] as const,
  stores: (siteId: string) => ['sites', siteId, 'stores'] as const,
  materials: ['materials'] as const,
  units: ['units'] as const,
  boqItems: (siteId: string) => ['boq-items', siteId] as const,
  stock: (storeId: string) => ['inventory', 'stock', storeId] as const,
  /** Every cached stock query, for the invalidation that follows any movement. */
  allStock: ['inventory', 'stock'] as const,
  ledger: (storeId: string, materialId: string) =>
    ['inventory', 'ledger', storeId, materialId] as const,
  receipts: (storeId: string, status: string) =>
    ['inventory', 'receipts', storeId, status] as const,
  allReceipts: ['inventory', 'receipts'] as const,
  issues: (storeId: string, status: string) => ['inventory', 'issues', storeId, status] as const,
  allIssues: ['inventory', 'issues'] as const,
};

/** Reference data changes about once a quarter; refetching it per screen is waste. */
const REFERENCE_STALE_TIME = 15 * 60_000;

export function useSites() {
  return useQuery({
    queryKey: inventoryKeys.sites,
    queryFn: async () => (await apiClient.get<Site[]>('/sites')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useStores(siteId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.stores(siteId ?? ''),
    queryFn: async () => (await apiClient.get<Store[]>(`/sites/${siteId}/stores`)).data,
    enabled: Boolean(siteId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useMaterials() {
  return useQuery({
    queryKey: inventoryKeys.materials,
    queryFn: async () =>
      (await apiClient.get<PageResponse<Material>>('/materials', { params: { size: 200 } })).data
        .content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useUnits() {
  return useQuery({
    queryKey: inventoryKeys.units,
    queryFn: async () => (await apiClient.get<Unit[]>('/units')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * A material named at the gate, for the delivery of something the catalogue has never heard
 * of.
 *
 * <p>The row it creates is marked provisional — a name off a challan, with no rate and no tax
 * behind it — so the office can find it later and say what it really is. The server answers a
 * name it already holds with the material it already holds, so typing "cement" here does not
 * quietly open a second cement whose stock is separate from the first.</p>
 */
export function useAddFieldMaterial() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string; baseUnitId: string }) =>
      (await apiClient.post<Material>('/materials/field', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.materials });
    },
  });
}

/**
 * The work items an issue can be charged to. Placeholder rows the tender parser emitted are
 * dropped here as well as refused by the server — offering one and then rejecting it is a
 * worse answer than never offering it.
 */
export function useBoqItems(siteId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.boqItems(siteId ?? ''),
    queryFn: async () => {
      const items = (await apiClient.get<BoqItem[]>('/boq-items', { params: { siteId } })).data;
      return items.filter((item) => !item.synthetic);
    },
    enabled: Boolean(siteId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useStock(storeId: string | undefined, lowOnly = false) {
  return useQuery({
    queryKey: [...inventoryKeys.stock(storeId ?? ''), lowOnly],
    queryFn: async () =>
      (await apiClient.get<StockPosition>('/inventory/stock', { params: { storeId, lowOnly } }))
        .data,
    enabled: Boolean(storeId),
  });
}

/** The movements behind one balance — what settles an argument about a stock figure. */
export function useLedger(storeId: string | undefined, materialId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.ledger(storeId ?? '', materialId ?? ''),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<LedgerRow>>('/inventory/ledger', {
          params: { storeId, materialId, size: 100 },
        })
      ).data,
    enabled: Boolean(storeId) && Boolean(materialId),
  });
}

export interface ReceiptInput {
  id: string;
  storeId: string;
  receiptDate: string;
  vendorId?: string | undefined;
  invoiceNumber?: string | undefined;
  challanNumber?: string | undefined;
  vehicleNumber?: string | undefined;
  lines: { materialId: string; unitId: string; quantity: number; rate: number }[];
}

/**
 * Books a delivery. The id is generated on the device, which is what makes a re-send safe:
 * a receipt entered at the gate and synced three times is one receipt, not three.
 */
export function useCreateReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: ReceiptInput) =>
      (await apiClient.post<Receipt>('/inventory/goods-receipts', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allReceipts });
    },
  });
}

/** Deliveries at a store in one workflow state — the engineer's sign-off queue. */
export function useReceipts(storeId: string | undefined, status: string) {
  return useQuery({
    queryKey: inventoryKeys.receipts(storeId ?? '', status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Receipt>>('/inventory/goods-receipts', {
          params: { storeId, status, size: 50 },
        })
      ).data,
    enabled: Boolean(storeId),
  });
}

export function useVerifyReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: 'VERIFY' | 'REJECT'; remarks?: string }) =>
      (
        await apiClient.post<Receipt>(`/inventory/goods-receipts/${input.id}/verify`, {
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      // Verifying is what puts material into the store, so the stock caches go too.
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allReceipts });
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allStock });
    },
  });
}

export interface IssueInput {
  id: string;
  storeId: string;
  issueDate: string;
  boqItemId?: string | undefined;
  purpose?: string | undefined;
  issuedToName?: string | undefined;
  workLocation?: string | undefined;
  lines: { materialId: string; unitId: string; quantity: number; boqItemId?: string }[];
}

export function useCreateIssue() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: IssueInput) =>
      (await apiClient.post<Issue>('/inventory/issues', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allIssues });
    },
  });
}

export function useIssues(storeId: string | undefined, status: string) {
  return useQuery({
    queryKey: inventoryKeys.issues(storeId ?? '', status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Issue>>('/inventory/issues', {
          params: { storeId, status, size: 50 },
        })
      ).data,
    enabled: Boolean(storeId),
  });
}

export function useApproveIssue() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: 'APPROVE' | 'REJECT'; remarks?: string }) =>
      (
        await apiClient.post<Issue>(`/inventory/issues/${input.id}/approve`, {
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allIssues });
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allStock });
    },
  });
}

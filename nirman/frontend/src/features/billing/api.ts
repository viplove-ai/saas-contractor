import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  Agreement,
  Bill,
  BillSummary,
  BoqItem,
  MeasurementLineInput,
  Project,
  PageResponse,
  Sheet,
  UnbilledSummary,
  Unit,
} from './types';

export const billingKeys = {
  projects: ['billing', 'projects'] as const,
  units: ['billing', 'units'] as const,
  boqItems: (projectId: string) => ['billing', 'boq-items', projectId] as const,
  agreement: (projectId: string) => ['billing', 'agreement', projectId] as const,
  sheets: (projectId: string, billed?: boolean) =>
    ['billing', 'sheets', projectId, billed ?? 'all'] as const,
  sheet: (id: string) => ['billing', 'sheet', id] as const,
  unbilled: (projectId: string, cutoff?: string) =>
    ['billing', 'unbilled', projectId, cutoff ?? 'all'] as const,
  bills: (projectId: string) => ['billing', 'bills', projectId] as const,
  bill: (id: string) => ['billing', 'bill', id] as const,
};

const REFERENCE_STALE_TIME = 15 * 60_000;

/*
  Nothing in billing is cached by the service worker, and that is deliberate. A stale
  quantity on a bill is worse than a missing one — the whole document is an assertion about
  money, and a figure that was true ten minutes ago is not an answer.
*/

/**
 * Projects to bill against.
 *
 * <p>`/projects` is paginated and hands back a `PageResponse`, not an array — the register
 * screens have always unwrapped `content` and this one has to as well. Asking for the maximum
 * page rather than the default 25 because this is a picker, not a register: a contractor with
 * thirty tenders would otherwise be unable to reach the last five, with nothing on screen to
 * say why.</p>
 */
export function useBillingProjects() {
  return useQuery({
    queryKey: billingKeys.projects,
    queryFn: async () =>
      (await apiClient.get<PageResponse<Project>>('/projects', { params: { size: 100 } })).data
        .content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useUnits() {
  return useQuery({
    queryKey: billingKeys.units,
    queryFn: async () => (await apiClient.get<Unit[]>('/units')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useBoqItems(projectId: string | undefined) {
  return useQuery({
    queryKey: billingKeys.boqItems(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<BoqItem[]>('/boq-items', { params: { projectId } })).data,
    enabled: Boolean(projectId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

// ------------------------------------------------------------------ the agreement

/**
 * The tender's own details. 204 means they have not been given yet, which is the state the
 * first bill of a tender is refused in — so `undefined` here is a real answer, not a failure.
 */
export function useAgreement(projectId: string | undefined) {
  return useQuery({
    queryKey: billingKeys.agreement(projectId ?? ''),
    queryFn: async () => {
      const response = await apiClient.get<Agreement | ''>(`/projects/${projectId}/agreement`);
      return response.status === 204 || !response.data ? null : (response.data as Agreement);
    },
    enabled: Boolean(projectId),
  });
}

export function useSaveAgreement(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Partial<Agreement>) =>
      (await apiClient.put<Agreement>(`/projects/${projectId}/agreement`, input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingKeys.agreement(projectId) });
    },
  });
}

// ------------------------------------------------------------------ measurement sheets

export function useSheets(projectId: string | undefined, billed?: boolean) {
  return useQuery({
    queryKey: billingKeys.sheets(projectId ?? '', billed),
    queryFn: async () =>
      (await apiClient.get<Sheet[]>('/measurement-sheets', { params: { projectId, billed } })).data,
    enabled: Boolean(projectId),
  });
}

export function useSheet(id: string | undefined) {
  return useQuery({
    queryKey: billingKeys.sheet(id ?? ''),
    queryFn: async () => (await apiClient.get<Sheet>(`/measurement-sheets/${id}`)).data,
    enabled: Boolean(id),
  });
}

export interface SaveSheetInput {
  projectId: string;
  siteId?: string | null;
  boqItemId: string;
  sheetSerial?: string | null;
  sheetType?: string;
  measuredOn: string;
  locationNote?: string | null;
  writtenTotal?: number | null;
  unitWeight?: number | null;
  remarks?: string | null;
  lines: MeasurementLineInput[];
}

export function useCreateSheet() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: SaveSheetInput) =>
      (await apiClient.post<Sheet>('/measurement-sheets', input)).data,
    onSuccess: (sheet) => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
      queryClient.setQueryData(billingKeys.sheet(sheet.id), sheet);
    },
  });
}

export function useUpdateSheet(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Omit<SaveSheetInput, 'projectId' | 'boqItemId'> & { version: number }) =>
      (await apiClient.put<Sheet>(`/measurement-sheets/${id}`, input)).data,
    onSuccess: (sheet) => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
      queryClient.setQueryData(billingKeys.sheet(sheet.id), sheet);
    },
  });
}

/** Refused by the server while the written and computed totals disagree. */
export function useSignSheet() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<Sheet>(`/measurement-sheets/${id}/sign`)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
  });
}

export function useDeleteSheet() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/measurement-sheets/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
  });
}

// ------------------------------------------------------------------ bills

export function useUnbilled(projectId: string | undefined, cutoff?: string) {
  return useQuery({
    queryKey: billingKeys.unbilled(projectId ?? '', cutoff),
    queryFn: async () =>
      (
        await apiClient.get<UnbilledSummary>(`/projects/${projectId}/unbilled`, {
          params: cutoff ? { cutoff } : undefined,
        })
      ).data,
    enabled: Boolean(projectId),
  });
}

export function useBills(projectId: string | undefined) {
  return useQuery({
    queryKey: billingKeys.bills(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<BillSummary[]>('/ra-bills', { params: { projectId } })).data,
    enabled: Boolean(projectId),
  });
}

export function useBill(id: string | undefined) {
  return useQuery({
    queryKey: billingKeys.bill(id ?? ''),
    queryFn: async () => (await apiClient.get<Bill>(`/ra-bills/${id}`)).data,
    enabled: Boolean(id),
  });
}

export function useCreateBill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { projectId: string; cutoffDate: string; title?: string }) =>
      (await apiClient.post<Bill>('/ra-bills', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
  });
}

export function useDecideBill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: string; remarks?: string }) =>
      (
        await apiClient.post<Bill>(`/ra-bills/${input.id}/decide`, {
          action: input.action,
          remarks: input.remarks,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
  });
}

export function useDiscardBill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/ra-bills/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
  });
}

/**
 * The blank master sheets, to print and take to the site.
 *
 * <p>Generic — one design for every tender, so this is not scoped to a project. The serials
 * are what the register's duplicate guard matches on, which is why a run is printed once and
 * the next run starts where the last one ended.</p>
 */
export async function downloadBlankSheets(from: number, count: number): Promise<void> {
  const response = await apiClient.get<Blob>('/measurement-sheets/blank', {
    params: { from, count },
    responseType: 'blob',
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `measurement-sheets-${String(from).padStart(6, '0')}.pdf`;
  anchor.click();
  URL.revokeObjectURL(url);
}

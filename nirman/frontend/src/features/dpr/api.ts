import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  BoqItem,
  CreateDprInput,
  Dpr,
  DprPrefill,
  DprWorkflow,
  PageResponse,
  Site,
  UpdateDprInput,
} from './types';

export const dprKeys = {
  sites: ['sites'] as const,
  boqItems: (siteId: string) => ['boq-items', siteId] as const,
  prefill: (siteId: string, date: string) => ['dprs', 'prefill', siteId, date] as const,
  list: (siteId: string, status: string) => ['dprs', 'list', siteId, status] as const,
  one: (id: string) => ['dprs', id] as const,
  all: ['dprs'] as const,
};

const REFERENCE_STALE_TIME = 15 * 60_000;

export function useSites() {
  return useQuery({
    queryKey: dprKeys.sites,
    queryFn: async () => (await apiClient.get<Site[]>('/sites')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useBoqItems(siteId: string | undefined) {
  return useQuery({
    queryKey: dprKeys.boqItems(siteId ?? ''),
    queryFn: async () =>
      (await apiClient.get<BoqItem[]>('/boq-items', { params: { siteId } })).data,
    enabled: Boolean(siteId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * What labour, inventory and expense already know about the day.
 *
 * <p>Deliberately never cached beyond the screen's own life: the figures are derived from
 * the records on every call, and the whole point of the prefill is that it matches them.
 * A stale prefill would show a supervisor a labour cost the roster no longer supports.</p>
 */
export function usePrefill(siteId: string | undefined, date: string) {
  return useQuery({
    queryKey: dprKeys.prefill(siteId ?? '', date),
    queryFn: async () =>
      (await apiClient.get<DprPrefill>('/dprs/prefill', { params: { siteId, date } })).data,
    enabled: Boolean(siteId) && Boolean(date),
    staleTime: 0,
  });
}

export function useDprs(siteId: string | undefined, status: DprWorkflow | '') {
  return useQuery({
    queryKey: dprKeys.list(siteId ?? '', status),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<Dpr>>('/dprs', {
          params: { siteId, status: status || undefined, size: 100 },
        })
      ).data,
  });
}

export function useDpr(id: string | undefined) {
  return useQuery({
    queryKey: dprKeys.one(id ?? ''),
    queryFn: async () => (await apiClient.get<Dpr>(`/dprs/${id}`)).data,
    enabled: Boolean(id),
  });
}

/**
 * Opens the day's report. The id is generated on the device, so a report typed at site with
 * no signal and synced three times is one report rather than three.
 */
export function useCreateDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateDprInput) => (await apiClient.post<Dpr>('/dprs', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
    },
  });
}

export function useUpdateDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: UpdateDprInput }) =>
      (await apiClient.put<Dpr>(`/dprs/${id}`, input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
    },
  });
}

export function useSubmitDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await apiClient.post<Dpr>(`/dprs/${id}/submit`)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
    },
  });
}

/**
 * The engineer's decision. Verifying posts the measured quantities to the measurement book,
 * so the caches that show contract progress go with it.
 */
export function useDecideDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: 'VERIFY' | 'REJECT'; remarks?: string }) =>
      (
        await apiClient.post<Dpr>(`/dprs/${input.id}/verify`, {
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['boq-items'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
}

/**
 * Downloads the printed report.
 *
 * <p>Fetched through the api client rather than opened in a new tab, because the PDF route
 * needs the Authorization header — a bare link would arrive without it and answer 401.</p>
 */
export async function downloadDprPdf(id: string, dprNumber: string): Promise<void> {
  const response = await apiClient.get<Blob>(`/dprs/${id}/pdf`, { responseType: 'blob' });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${dprNumber}.pdf`;
  anchor.click();
  URL.revokeObjectURL(url);
}

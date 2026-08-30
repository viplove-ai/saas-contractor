import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import { compressPhoto } from '../../offline/uploads';
import type {
  BoqItem,
  CreateDprInput,
  DayCounts,
  Dpr,
  DprMaterial,
  DprPrefill,
  DprWorkflow,
  LabourCountLine,
  PageResponse,
  PlantRateInput,
  PrintSection,
  Site,
  SupplierDayLine,
  UpdateDprInput,
} from './types';
import { PRINT_SECTIONS } from './types';

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
 * Takes a report off the register and gives its day back.
 *
 * <p>Not the same act as the wizard's "start fresh", which empties a report and writes over
 * it. This one is for the report that should not exist — the wrong site, a day opened out of
 * habit — and it is refused once the report has gone for signature. The prefill goes with it
 * because the day is free again, and the dashboards because their counts have changed.</p>
 */
export function useDeleteDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) =>
      (await apiClient.delete<Dpr>(`/dprs/${id}`, { data: { reason } })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
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
 * The office's final approval of a report the engineer has signed.
 *
 * <p>It claims nothing — the measured quantities reached the measurement book at verification —
 * so the BOQ queries are deliberately not invalidated here. Only the report and the tiles that
 * count reports by state have moved.</p>
 */
export function useApproveDpr() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<Dpr>(`/dprs/${id}/approval`, {})).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
}

/**
 * What the plant on a handed-over report is charged at.
 *
 * <p>Its own call rather than part of the save, because the plant rows are the supervisor's
 * half of the report and the rate on them is not his. Nothing is claimed off it, so the BOQ
 * caches stay where they are; the report itself has changed and is re-read.</p>
 */
export function useSetPlantRates() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; rates: PlantRateInput[] }) =>
      (await apiClient.put<Dpr>(`/dprs/${input.id}/plant-rates`, { rates: input.rates })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
    },
  });
}

/**
 * Downloads the printed report.
 *
 * <p>Fetched through the api client rather than opened in a new tab, because the PDF route
 * needs the Authorization header — a bare link would arrive without it and answer 401.</p>
 */
/**
 * The printed form, whole or as an extract.
 *
 * <p>{@link sections} is what to include. Passing every one of them, or none at all, prints
 * the whole report — an empty tick list is somebody who changed their mind, not a request for
 * a sheet with nothing but a letterhead on it. Anything left out is named in a line at the
 * foot of the page by the server, which is what makes offering the choice honest: a copy with
 * no plant table and nothing saying so reads as a site that had no plant.</p>
 */
export async function downloadDprPdf(
  id: string,
  dprNumber: string,
  sections?: PrintSection[],
): Promise<void> {
  const response = await apiClient.get<Blob>(`/dprs/${id}/pdf`, {
    responseType: 'blob',
    ...(sections && sections.length > 0 && sections.length < PRINT_SECTIONS.length
      ? { params: { sections }, paramsSerializer: { indexes: null } }
      : {}),
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${dprNumber}.pdf`;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * A short-lived signed link to a stored file, asked for when a thumbnail is about to be drawn.
 *
 * <p>Not carried on the report's own photo rows: the server re-checks the caller's site access
 * every time it signs one, and signing a link per photograph for a register nobody opens would
 * be a check per picture that nobody looks at. Cached for half the link's own life — it dies
 * after ten minutes and a dead one draws a broken image, so it is refetched well before that
 * and not on every re-render.</p>
 */
export function useDprPhotoUrl(attachmentId: string | undefined) {
  return useQuery({
    queryKey: ['dpr', 'attachment-url', attachmentId ?? ''] as const,
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60_000,
    gcTime: 5 * 60_000,
  });
}

// ------------------------------------------------------------------ entering the day here

/**
 * Everything below exists so the report can be the <b>only</b> screen a supervisor opens.
 *
 * <p>He may enter material and spending as the day happens, on their own screens, and then
 * the report fills itself in. Or he may enter none of it and type the lot here at six
 * o'clock. Both have to produce the same records, so these hooks post to the same endpoints
 * the dedicated screens do rather than to some report-shaped shortcut. A receipt entered
 * here is a goods receipt: it lands in the stock ledger, it is refused if the month is
 * closed, and the storekeeper's screen shows it. The alternative — letting the report hold
 * its own typed figures — would make the ledger and the report two different answers to
 * "how much cement came in", which is the one thing this system refuses to have.</p>
 *
 * <p>Each is a separate call and each stands on its own. A receipt that saved is not undone
 * because the expense after it was refused as a duplicate: they are unrelated facts about
 * the day, and rolling back a true one to punish a doubtful one helps nobody.</p>
 */

export const labourCountKeys = {
  day: (siteId: string, date: string) => ['labour-counts', siteId, date] as const,
  all: ['labour-counts'] as const,
};

export function useLabourCounts(siteId: string | undefined, date: string) {
  return useQuery({
    queryKey: labourCountKeys.day(siteId ?? '', date),
    queryFn: async () =>
      (await apiClient.get<DayCounts>('/labour-counts', { params: { siteId, date } })).data,
    enabled: Boolean(siteId) && Boolean(date),
    staleTime: 0,
  });
}

/** PUT, because the request is the whole day: re-sending it is not a second day. */
export function useSaveLabourCounts() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      siteId: string;
      date: string;
      lines: LabourCountLine[];
      suppliers: SupplierDayLine[];
    }) => (await apiClient.put<DayCounts>('/labour-counts', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: labourCountKeys.all });
      // The report reads the counts back through the prefill, so it has to be re-asked.
      void queryClient.invalidateQueries({ queryKey: ['dprs', 'prefill'] });
    },
  });
}

/** Reference data for the counts editor. Duplicated from the labour feature on purpose:
 *  features do not import one another, and this is two queries rather than a shared module. */
export function useSkillCategories() {
  return useQuery({
    queryKey: ['skill-categories'],
    queryFn: async () =>
      (await apiClient.get<{ id: string; code: string; name: string }[]>('/skill-categories')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * The men who bring gangs, off the one supplier register.
 *
 * <p>There is no separate contractor list any more (V23): a man who supplies labour is a
 * supplier like the one who supplies cement, registered once and carrying one account. The
 * type is what narrows the picker to the ones who send people rather than material.</p>
 */
export function useLabourSuppliers() {
  return useQuery({
    queryKey: ['labour-suppliers'],
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<{ id: string; code: string; name: string }>>('/vendors', {
          params: { type: 'SUBCONTRACTOR', active: true, size: 100 },
        })
      ).data.content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useMaterials() {
  return useQuery({
    queryKey: ['materials'],
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<DprMaterial>>('/materials', { params: { size: 200 } })
      ).data.content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** Only needed for a material being named here — a picked one brings its own base unit. */
export function useUnits() {
  return useQuery({
    queryKey: ['units'],
    queryFn: async () =>
      (await apiClient.get<{ id: string; code: string; name: string }[]>('/units')).data,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * A material named at the gate, when the catalogue has no name for what arrived.
 *
 * <p>Duplicated from the inventory feature rather than imported, the same way the material
 * and store lookups above are: features do not reach into one another, and this is one
 * mutation rather than a shared module.</p>
 */
export function useAddFieldMaterial() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string; baseUnitId: string }) =>
      (await apiClient.post<DprMaterial>('/materials/field', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['materials'] });
    },
  });
}

export function useStores(siteId: string | undefined) {
  return useQuery({
    queryKey: ['stores', siteId ?? ''],
    queryFn: async () =>
      (await apiClient.get<{ id: string; code: string; name: string }[]>(`/sites/${siteId}/stores`))
        .data,
    enabled: Boolean(siteId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * The heads an expense can be booked against.
 *
 * <p>Narrowed to the leaves. "Material" and "Labour" are headings that group the real heads
 * and booking against one loses the distinction the costing rules turn on — a labour payment
 * settles a wage already counted through attendance, and a material purchase is inventory
 * rather than cost. A heading answers neither question.</p>
 *
 * <p>Leaf by structure rather than by "has a parent", so an organisation with a flat list
 * keeps every one of its heads instead of a picker with nothing in it.</p>
 */
export function useExpenseCategories() {
  return useQuery({
    queryKey: ['expense-categories'],
    queryFn: async () => {
      const all = (
        await apiClient.get<ExpenseCategory[]>('/expense-categories')
      ).data;
      const parents = new Set(all.map((category) => category.parentId).filter(Boolean));
      return all.filter((category) => !parents.has(category.id));
    },
    staleTime: REFERENCE_STALE_TIME,
  });
}

interface ExpenseCategory {
  id: string;
  code: string;
  name: string;
  parentId?: string;
}

/** Material that arrived at the store today. A real goods receipt, not a note on a report. */
export function useReceiveMaterial() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      storeId: string;
      receiptDate: string;
      lines: { materialId: string; unitId: string; quantity: number; rate: number }[];
    }) => (await apiClient.post('/inventory/goods-receipts', input)).data,
    onSuccess: () => invalidateDay(queryClient),
  });
}

/** Material that went to the work face today. Consumption, which is what costs money. */
export function useIssueMaterial() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      storeId: string;
      issueDate: string;
      purpose?: string | undefined;
      lines: { materialId: string; unitId: string; quantity: number }[];
    }) => (await apiClient.post('/inventory/issues', input)).data,
    onSuccess: () => invalidateDay(queryClient),
  });
}

export function useAddExpense() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      siteId: string;
      expenseDate: string;
      categoryId: string;
      description: string;
      amountBeforeTax: number;
      gstPercent: number;
    }) => (await apiClient.post('/expenses', input)).data,
    onSuccess: () => invalidateDay(queryClient),
  });
}

/**
 * A photograph of the day's work, in two calls: the file into storage, then the link onto
 * the report. That order on purpose — an attachment with no owner is a stray file, while a
 * link to an attachment that does not exist is a broken row on a signed report.
 */
export function useAttachDprPhoto() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { dprId: string; siteId: string; file: File; caption?: string }) => {
      const photo = await compressPhoto(input.file);
      const form = new FormData();
      form.append('file', photo.blob, photo.fileName);
      const created = await apiClient.post<{ id: string }>('/attachments', form, {
        params: { ownerEntityType: 'DPR', kind: 'PHOTO', siteId: input.siteId },
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return (
        await apiClient.post(`/dprs/${input.dprId}/photos`, {
          attachmentId: created.data.id,
          caption: input.caption || undefined,
        })
      ).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dprKeys.all });
    },
  });
}

/**
 * One place to say what a day's entry invalidates. The prefill above all — it is the whole
 * reason the supervisor can see that what he just typed arrived.
 */
function invalidateDay(queryClient: ReturnType<typeof useQueryClient>): void {
  void queryClient.invalidateQueries({ queryKey: ['dprs', 'prefill'] });
  void queryClient.invalidateQueries({ queryKey: ['inventory'] });
  void queryClient.invalidateQueries({ queryKey: ['expenses'] });
  void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
}

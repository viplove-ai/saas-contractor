import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type { SheetGeometry } from './reader/geometry';
import type {
  Agreement,
  AgreementSuggestion,
  Bill,
  BillSummary,
  BoqItem,
  MeasurementLineInput,
  Project,
  PageResponse,
  ReferenceDocument,
  Sheet,
  TenderDocument,
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

/**
 * The bill as the workbook that goes to the division — Front Page, Measurement Book, Abstract
 * of Cost, Bill Form, Recovery Statement and Deviation Statement, linked by live formulas.
 *
 * <p>A bill that has not been passed downloads too, and says DRAFT in its own file name. Taking
 * the file to the department to have the measurements checked before submitting is what a draft
 * is for.</p>
 */
export async function downloadBillExcel(id: string, title: string): Promise<void> {
  const response = await apiClient.get<Blob>(`/ra-bills/${id}/excel`, { responseType: 'blob' });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${title.replace(/[^A-Za-z0-9._-]/g, '-')}.xlsx`;
  anchor.click();
  URL.revokeObjectURL(url);
}

// ------------------------------------------------------------------ the vault

export const vaultKeys = {
  documents: (kind?: string) => ['billing', 'vault', kind ?? 'all'] as const,
  tenderDocuments: (projectId: string) => ['billing', 'vault', 'tender', projectId] as const,
  suggestion: (projectId: string) => ['billing', 'vault', 'suggestion', projectId] as const,
};

export function useReferenceDocuments(kind?: string) {
  return useQuery({
    queryKey: vaultKeys.documents(kind),
    queryFn: async () =>
      (
        await apiClient.get<ReferenceDocument[]>('/reference-documents', {
          params: kind ? { kind } : undefined,
        })
      ).data,
  });
}

export function useSaveReferenceDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id?: string } & Partial<ReferenceDocument>) => {
      const { id, ...body } = input;
      return id
        ? (await apiClient.put<ReferenceDocument>(`/reference-documents/${id}`, body)).data
        : (await apiClient.post<ReferenceDocument>('/reference-documents', body)).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing', 'vault'] });
    },
  });
}

/** Marks an edition replaced. Moves nothing that already cites it — that is the whole point. */
export function useSupersedeDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; replacedBy: string }) =>
      (
        await apiClient.post<ReferenceDocument>(`/reference-documents/${input.id}/supersede`, {
          replacedBy: input.replacedBy,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing', 'vault'] });
    },
  });
}

export function useWithdrawDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/reference-documents/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing', 'vault'] });
    },
  });
}

/**
 * Uploads the file, then points the edition at it. Two calls because the file is its own row —
 * an edition can exist before anybody finds a copy, and the copy can be replaced later.
 *
 * <p>Two things this call has to get right, and both were wrong first time. `ownerEntityType`
 * is required by the server and decides where the object is filed; omitting it produced an
 * unhandled error and the generic "request could not be completed". And apiClient sends JSON
 * by default, so a multipart body has to ask for its own boundary or the parts never arrive.</p>
 */
export async function uploadDocumentFile(documentId: string, file: File): Promise<void> {
  const form = new FormData();
  form.append('file', file, file.name);
  const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
    params: { ownerEntityType: 'REFERENCE_DOCUMENT', kind: 'DOCUMENT' },
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  await apiClient.post(`/reference-documents/${documentId}/attach`, {
    attachmentId: uploaded.data.id,
  });
}

export function useTenderDocuments(projectId: string | undefined) {
  return useQuery({
    queryKey: vaultKeys.tenderDocuments(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<TenderDocument[]>(`/projects/${projectId}/agreement/documents`)).data,
    enabled: Boolean(projectId),
  });
}

export function useLinkTenderDocuments(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (documentIds: string[]) =>
      (
        await apiClient.put<TenderDocument[]>(`/projects/${projectId}/agreement/documents`,
          documentIds.map((documentId) => ({ documentId, role: 'SCHEDULE_OF_RATES' })))
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['billing', 'vault'] });
    },
  });
}

/** What the tender notice said. Suggestions only — nothing here decides anything. */
export function useAgreementSuggestion(projectId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: vaultKeys.suggestion(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<AgreementSuggestion>(`/projects/${projectId}/agreement/suggestion`)).data,
    enabled: Boolean(projectId) && enabled,
  });
}

/**
 * Where every box sits on the printed page.
 *
 * <p>Fetched, never duplicated: the PDF is rendered from these millimetres and the reader
 * corrects a photograph onto the same ones. Cached hard because it changes only when the sheet
 * design does, and a reader that re-fetched it per photo would be useless in a valley.</p>
 */
export function useSheetGeometry(enabled: boolean) {
  return useQuery({
    queryKey: ['billing', 'sheet-geometry'] as const,
    queryFn: async () =>
      (await apiClient.get<SheetGeometry>('/measurement-sheets/geometry')).data,
    enabled,
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

/**
 * Where the next run of blank sheets should start.
 *
 * <p>One serial, one sheet, for ever — the register refuses a number it has already seen, so a
 * run that reprinted numbers would produce paper nobody could enter. Suggested, never imposed:
 * the system knows about sheets that were entered, not about the forty blanks still in the
 * book, and only the person holding the book knows that.</p>
 */
export function useNextSerial(enabled: boolean) {
  return useQuery({
    queryKey: ['billing', 'next-serial'] as const,
    queryFn: async () =>
      (
        await apiClient.get<{ nextSerial: number; lastUsed: number | null }>(
          '/measurement-sheets/next-serial',
        )
      ).data,
    enabled,
    staleTime: 0,
  });
}

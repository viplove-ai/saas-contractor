import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { compressPhoto } from '../../offline/uploads';
import { apiClient } from '../../shared/apiClient';
import type {
  BoqItem,
  Equipment,
  EquipmentCondition,
  EquipmentOwnership,
  Issue,
  LedgerRow,
  Material,
  PageResponse,
  Receipt,
  Site,
  StockPosition,
  StockCorrection,
  Store,
  Unit,
  Vendor,
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
  equipment: (storeId: string) => ['inventory', 'equipment', storeId] as const,
  allEquipment: ['inventory', 'equipment'] as const,
  corrections: (storeId: string) => ['inventory', 'stock-corrections', storeId] as const,
  allCorrections: ['inventory', 'stock-corrections'] as const,
  /** One signed link per attachment, shared by every thumbnail drawn from it. */
  attachmentUrl: (attachmentId: string) => ['attachments', attachmentId, 'url'] as const,
  vendors: ['vendors'] as const,
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
  /** For the attachments: a file is scoped to the site, and the server re-checks it. */
  siteId: string;
  storeId: string;
  receiptDate: string;
  vendorId?: string | undefined;
  invoiceNumber?: string | undefined;
  challanNumber?: string | undefined;
  vehicleNumber?: string | undefined;
  /** What came off the lorry. Required: the load is gone by the time anybody asks. */
  materialPhoto: File;
  /** The invoice or challan that came with it — what the supplier says he sent. */
  invoicePhoto: File;
  /** The rate is left out unless the caller holds inventory:price — the server refuses it. */
  lines: { materialId: string; unitId: string; quantity: number; rate?: number }[];
}

/**
 * Books a delivery. The id is generated on the device, which is what makes a re-send safe:
 * a receipt entered at the gate and synced three times is one receipt, not three.
 *
 * <p>Three calls on the wire and one act to the storekeeper: the two pictures into storage,
 * then the delivery that points at them. In that order, because a receipt naming a file that
 * does not exist is a broken thumbnail on the office's screen while a file nothing points at
 * is only a stray upload — and because the server refuses the delivery without them, which is
 * the rule the form is only being polite about.</p>
 */
export function useCreateReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ materialPhoto, invoicePhoto, siteId, ...body }: ReceiptInput) => {
      const materialPhotoId = await uploadMovementPhoto(materialPhoto, 'GOODS_RECEIPT', siteId);
      const invoicePhotoId = await uploadMovementPhoto(invoicePhoto, 'GOODS_RECEIPT', siteId);
      return (
        await apiClient.post<Receipt>('/inventory/goods-receipts', {
          ...body,
          materialPhotoId,
          invoicePhotoId,
        })
      ).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allReceipts });
    },
  });
}

/**
 * One picture into storage, shrunk on the way.
 *
 * <p>A photograph of a load off a modern phone is four megabytes of somebody's morning on a
 * site connection, and 1600 pixels on the long edge is enough to count bags. A PDF invoice
 * passes through untouched — there is nothing to resize.</p>
 */
async function uploadMovementPhoto(
  file: File,
  ownerEntityType: 'GOODS_RECEIPT' | 'MATERIAL_ISSUE',
  siteId: string,
): Promise<string> {
  const photo = await compressPhoto(file);
  const form = new FormData();
  form.append('file', photo.blob, photo.fileName);
  const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
    params: { ownerEntityType, kind: 'PHOTO', siteId },
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return uploaded.data.id;
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

/**
 * What the office says the delivery cost. Its own call, because pricing and checking
 * against the challan are two acts — and on this screen usually two people, twenty minutes
 * apart.
 */
export function usePriceReceipt() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      id: string;
      lines: { lineId: string; rate: number; gstPercent?: number }[];
    }) =>
      (await apiClient.put<Receipt>(`/inventory/goods-receipts/${input.id}/prices`, {
        lines: input.lines,
      })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allReceipts });
    },
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
  /** For the attachment: a file is scoped to the site, and the server re-checks it. */
  siteId: string;
  storeId: string;
  issueDate: string;
  boqItemId?: string | undefined;
  purpose?: string | undefined;
  issuedToName?: string | undefined;
  workLocation?: string | undefined;
  /** What went out. One picture: there is no third party on an issue and no paper. */
  materialPhoto: File;
  lines: { materialId: string; unitId: string; quantity: number; boqItemId?: string }[];
}

export function useCreateIssue() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ materialPhoto, siteId, ...body }: IssueInput) => {
      const materialPhotoId = await uploadMovementPhoto(materialPhoto, 'MATERIAL_ISSUE', siteId);
      return (
        await apiClient.post<Issue>('/inventory/issues', { ...body, materialPhotoId })
      ).data;
    },
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

// ---------------------------------------------------------------- equipment

/**
 * The suppliers, for saying who a hired machine came from. Its own copy rather than the
 * expense module's: features do not import one another, and the query key is shared so the
 * two screens still hit the cache once between them.
 */
export function useVendors() {
  return useQuery({
    queryKey: inventoryKeys.vendors,
    queryFn: async () =>
      (await apiClient.get<PageResponse<Vendor>>('/vendors', { params: { size: 200 } })).data
        .content,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * The plant standing at one store, pending entries included.
 *
 * <p>Pending rows are in the list rather than hidden until somebody accepts them: the man who
 * entered the mixer needs to see that he did, or he enters it again on Thursday.</p>
 */
export function useEquipment(storeId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.equipment(storeId ?? ''),
    queryFn: async () =>
      (await apiClient.get<Equipment[]>('/inventory/equipment', { params: { storeId } })).data,
    enabled: Boolean(storeId),
  });
}

export interface EquipmentInput {
  id: string;
  storeId: string;
  name: string;
  assetCode?: string | undefined;
  quantity: number;
  ownership: EquipmentOwnership;
  condition: EquipmentCondition;
  supplierId?: string | undefined;
  remarks?: string | undefined;
}

/**
 * Enters a machine. The id is generated on the device, so an entry typed in a yard with no
 * signal and sent three times is one machine.
 */
export function useAddEquipment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: EquipmentInput) =>
      (await apiClient.post<Equipment>('/inventory/equipment', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
  });
}

/** The office accepting a machine onto the register, or saying it is not there. */
export function useDecideEquipment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: 'ACCEPT' | 'REJECT'; remarks?: string }) =>
      (
        await apiClient.post<Equipment>(`/inventory/equipment/${input.id}/decision`, {
          action: input.action,
          remarks: input.remarks || undefined,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
  });
}

export function useUpdateEquipment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: EquipmentInput & { version: number }) => {
      const { id, ...body } = input;
      return (await apiClient.put<Equipment>(`/inventory/equipment/${id}`, body)).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
  });
}

/**
 * The picture of a machine, in two calls: the file into storage, then its id onto the entry.
 *
 * <p>That order on purpose, and the same one the DPR's photographs take. An attachment with
 * no owner is a stray file somebody can clean up; a register row pointing at an attachment
 * that does not exist is a broken picture on the office's screen.</p>
 *
 * <p>No offline queue behind this one. The register itself is an online screen — entering a
 * machine posts straight to the server — and a photograph that queued while its row did not
 * would be a file waiting for an owner that may never be created.</p>
 */
/**
 * Adding pictures to a machine: the files into storage, then the ids onto the entry.
 *
 * <p>One request for the batch rather than one per picture. Somebody standing at the machine
 * photographs the plate and the damage in one go, and sending them separately is where half of
 * them are lost on a site connection — and it would re-open the row once per picture.</p>
 *
 * <p>Each is shrunk on the way. A photograph off a modern phone is four megabytes of
 * somebody's morning, and 1600 pixels on the long edge is enough to read an asset plate.</p>
 */
export function useAddEquipmentPhotos() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { equipmentId: string; siteId: string; files: File[] }) => {
      const attachmentIds: string[] = [];
      for (const file of input.files) {
        const photo = await compressPhoto(file);
        const form = new FormData();
        form.append('file', photo.blob, photo.fileName);
        const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
          params: { ownerEntityType: 'SITE_EQUIPMENT', kind: 'PHOTO', siteId: input.siteId },
          headers: { 'Content-Type': 'multipart/form-data' },
        });
        attachmentIds.push(uploaded.data.id);
      }
      return (
        await apiClient.post<Equipment>(
          `/inventory/equipment/${input.equipmentId}/photos`,
          { attachmentIds },
        )
      ).data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
  });
}

/**
 * Takes one picture off the entry, and the file with it.
 *
 * <p>Named by the photograph's own row rather than by the file behind it, so nothing can
 * unpick a file from a machine it never belonged to.</p>
 */
export function useRemoveEquipmentPhoto() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { equipmentId: string; photoId: string }) =>
      (
        await apiClient.delete<Equipment>(
          `/inventory/equipment/${input.equipmentId}/photos/${input.photoId}`,
        )
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
  });
}

/**
 * A short-lived signed link to a stored file, asked for when a thumbnail is about to be drawn.
 *
 * <p>Not carried on the register's own rows: the server re-checks the caller's site access
 * every time it signs one, and signing forty links for a register nobody scrolls through
 * would be forty checks for one picture somebody looks at.</p>
 *
 * <p>Cached for half the link's own life. The URL dies after ten minutes and a dead one draws
 * a broken image, so it is refetched well before that — but not on every re-render, which on
 * a site phone would be a request per keystroke elsewhere on the screen.</p>
 */
export function useAttachmentUrl(attachmentId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.attachmentUrl(attachmentId ?? ''),
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60_000,
    gcTime: 5 * 60_000,
  });
}

export function useDeleteEquipment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/inventory/equipment/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allEquipment });
    },
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

/**
 * The corrections asked for at this store, decided and undecided.
 *
 * <p>Refused ones are in the list on purpose. A request that disappeared the moment it was
 * turned down would be a storekeeper who counts the shed once and never types it again.</p>
 */
export function useStockCorrections(storeId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.corrections(storeId ?? ''),
    queryFn: async () =>
      (
        await apiClient.get<StockCorrection[]>('/inventory/stock-corrections', {
          params: { storeId },
        })
      ).data,
    enabled: Boolean(storeId),
  });
}

/**
 * Asking for a stock figure to be put right. Posts nothing — an administrator decides, and
 * accepting is what writes the adjustment onto the ledger.
 *
 * <p>The id is generated here, like every other entry the field makes: a count typed in a shed
 * with no signal and synced three times is one request, not three corrections in the queue.</p>
 */
export function useRaiseStockCorrection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      storeId: string;
      materialId: string;
      unitId: string;
      quantityDelta: number;
      correctionDate: string;
      reason: string;
    }) =>
      (
        await apiClient.post<StockCorrection>('/inventory/stock-corrections', {
          id: crypto.randomUUID(),
          ...input,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allCorrections });
    },
  });
}

/** The office answering one. Accepting moves the balance, so the stock queries go with it. */
export function useDecideStockCorrection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; action: 'ACCEPT' | 'REJECT'; remarks?: string }) =>
      (
        await apiClient.post<StockCorrection>(
          `/inventory/stock-corrections/${input.id}/decision`,
          { action: input.action, remarks: input.remarks || undefined },
        )
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allCorrections });
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allStock });
    },
  });
}

/**
 * Correcting the name a material was given at the gate.
 *
 * <p>The other half of naming one. Without it "celment" sits on every picker until somebody in
 * the office notices, and the man who typed it works around his own mistake by naming a second
 * row — which is the split balance the naming rule exists to prevent, reached from inside.</p>
 *
 * <p>The name and nothing else: not the rate, not the unit. Changing what a material is
 * measured in re-reads every quantity ever booked against it.</p>
 */
export function useCorrectFieldMaterial() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; name: string; version: number }) =>
      (
        await apiClient.put<Material>(`/materials/${input.id}/field`, {
          name: input.name,
          version: input.version,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.materials });
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.allStock });
    },
  });
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import { compressPhoto } from '../../offline/uploads';
import type {
  EmploymentType,
  SalaryRevision,
  StaffDashboard,
  StaffDocument,
  StaffDocumentType,
  StaffProfile,
} from './types';

export const staffKeys = {
  all: ['staff'] as const,
  list: ['staff', 'list'] as const,
  dashboard: ['staff', 'dashboard'] as const,
  profile: (userId: string) => ['staff', 'profile', userId] as const,
  salary: (userId: string) => ['staff', 'salary', userId] as const,
  documents: (userId: string) => ['staff', 'documents', userId] as const,
  attachmentUrl: (attachmentId: string) => ['attachments', attachmentId, 'url'] as const,
};

export function useStaff() {
  return useQuery({
    queryKey: staffKeys.list,
    queryFn: async () => (await apiClient.get<StaffProfile[]>('/staff')).data,
  });
}

export function useStaffDashboard() {
  return useQuery({
    queryKey: staffKeys.dashboard,
    queryFn: async () => (await apiClient.get<StaffDashboard>('/staff/dashboard')).data,
  });
}

export function useSalaryHistory(userId: string | undefined) {
  return useQuery({
    queryKey: staffKeys.salary(userId ?? ''),
    queryFn: async () =>
      (await apiClient.get<SalaryRevision[]>(`/staff/${userId}/salary`)).data,
    enabled: Boolean(userId),
  });
}

export interface StaffProfileInput {
  userId: string;
  alternateMobile?: string | undefined;
  dateOfBirth?: string | undefined;
  aadhaarLast4?: string | undefined;
  pan?: string | undefined;
  currentAddress?: string | undefined;
  permanentAddress?: string | undefined;
  emergencyContactName?: string | undefined;
  emergencyContactMobile?: string | undefined;
  emergencyContactRelation?: string | undefined;
  bankAccountName?: string | undefined;
  bankAccountNo?: string | undefined;
  bankIfsc?: string | undefined;
  bankName?: string | undefined;
  employmentType: EmploymentType;
  joinedOn?: string | undefined;
  probationDays?: number | undefined;
  probationMonthlySalary?: number | undefined;
  confirmedMonthlySalary?: number | undefined;
  confirmedOn?: string | undefined;
  contractEndsOn?: string | undefined;
  exitDate?: string | undefined;
  exitReason?: string | undefined;
  notes?: string | undefined;
  /** Absent on the first save; the server treats its absence as "create". */
  version?: number | undefined;
}

export function useSaveStaffProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ userId, ...body }: StaffProfileInput) =>
      (await apiClient.put<StaffProfile>(`/staff/${userId}`, blankToUndefined(body))).data,
    onSuccess: () => {
      // The list, the dashboard and the one profile all moved; the dashboard counts records
      // that do not exist, so a first save changes a tile as well as a row.
      void queryClient.invalidateQueries({ queryKey: staffKeys.all });
    },
  });
}

export interface SalaryRevisionInput {
  userId: string;
  monthlyAmount: number;
  effectiveFrom: string;
  reason: string;
}

/** Appended, never edited: a raise in April must not rewrite what March cost. */
export function useRecordSalary() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ userId, ...body }: SalaryRevisionInput) =>
      (await apiClient.post<SalaryRevision>(`/staff/${userId}/salary`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: staffKeys.all });
    },
  });
}

/**
 * An empty box means "not given", which the server stores as null. Sending "" would put a
 * blank Aadhaar on file as an Aadhaar, and the difference shows the day somebody filters on
 * who is missing one.
 */
function blankToUndefined<T extends object>(input: T): T {
  return Object.fromEntries(
    Object.entries(input).map(([key, value]) => [key, value === '' ? undefined : value]),
  ) as T;
}

// ------------------------------------------------------------------ the papers

export function useStaffDocuments(userId: string | undefined) {
  return useQuery({
    queryKey: staffKeys.documents(userId ?? ''),
    queryFn: async () =>
      (await apiClient.get<StaffDocument[]>(`/staff/${userId}/documents`)).data,
    enabled: Boolean(userId),
  });
}

/**
 * The link behind one paper, signed fresh and short-lived.
 *
 * <p>Asked for per thumbnail rather than carried on the rows, exactly as the plant register
 * does it: the server re-checks the caller before it signs, the link dies in minutes, and a
 * record with six papers on it would otherwise mint six links for the one somebody opens.</p>
 */
export function useStaffDocumentUrl(attachmentId: string | undefined) {
  return useQuery({
    queryKey: staffKeys.attachmentUrl(attachmentId ?? ''),
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60_000,
    gcTime: 5 * 60_000,
  });
}

export interface AddStaffDocumentInput {
  userId: string;
  file: File;
  docType: StaffDocumentType;
  note?: string | undefined;
}

/**
 * Two calls on the wire and one act to the person doing it: the file into storage, then the
 * row that says whose it is and what it is. In that order, because a row pointing at a file
 * that does not exist is a broken thumbnail on the office's screen, while a file nothing
 * points at is only a stray file.
 *
 * <p>A photograph is shrunk on the way — a scan of a card off a modern phone is four
 * megabytes of somebody's morning, and 1600 pixels on the long edge is enough to read an
 * Aadhaar number off. A PDF passes through untouched; there is nothing to resize.</p>
 */
export function useAddStaffDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: AddStaffDocumentInput) => {
      const photo = await compressPhoto(input.file);
      const form = new FormData();
      form.append('file', photo.blob, photo.fileName);
      const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
        params: { ownerEntityType: 'STAFF_DOCUMENT', kind: 'DOCUMENT' },
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return (
        await apiClient.post<StaffDocument>(`/staff/${input.userId}/documents`, {
          attachmentId: uploaded.data.id,
          docType: input.docType,
          note: input.note || undefined,
        })
      ).data;
    },
    onSuccess: (_document, input) => {
      void queryClient.invalidateQueries({ queryKey: staffKeys.documents(input.userId) });
    },
  });
}

/** Off the record and out of storage together — see StaffDocumentService for why really. */
export function useRemoveStaffDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { userId: string; documentId: string }) => {
      await apiClient.delete(`/staff/${input.userId}/documents/${input.documentId}`);
    },
    onSuccess: (_void, input) => {
      void queryClient.invalidateQueries({ queryKey: staffKeys.documents(input.userId) });
    },
  });
}

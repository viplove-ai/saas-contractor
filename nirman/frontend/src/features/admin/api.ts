import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  AdminProject,
  AdminSite,
  AdminUser,
  BoqItem,
  ConfirmedBoqLine,
  NitDocument,
  NitFields,
  NitPreview,
  PageResponse,
  ProjectStatus,
  RoleOption,
  SiteStatus,
  UnitOption,
} from './types';

export const adminKeys = {
  users: (q: string, role: string) => ['admin', 'users', q, role] as const,
  allUsers: ['admin', 'users'] as const,
  roles: ['admin', 'roles'] as const,
  sites: (projectId: string) => ['admin', 'sites', projectId] as const,
  allSites: ['admin', 'sites'] as const,
  projects: ['admin', 'projects'] as const,
  project: (id: string) => ['admin', 'project', id] as const,
  boqItems: (projectId: string) => ['admin', 'boq-items', projectId] as const,
  nitDocument: (projectId: string) => ['admin', 'nit-document', projectId] as const,
  units: ['admin', 'units'] as const,
};

/** A contractor's whole staff fits on one page; paging this list would only hide people. */
const PAGE_SIZE = 100;

export function useUsers(q: string, role: string) {
  return useQuery({
    queryKey: adminKeys.users(q, role),
    queryFn: async () =>
      (
        await apiClient.get<PageResponse<AdminUser>>('/users', {
          params: { q: q || undefined, role: role || undefined, size: PAGE_SIZE },
        })
      ).data,
  });
}

export function useRoles() {
  return useQuery({
    queryKey: adminKeys.roles,
    queryFn: async () => (await apiClient.get<RoleOption[]>('/roles')).data,
    staleTime: Infinity,    // the four system roles only change by migration
  });
}

export interface CreateUserInput {
  username: string;
  fullName: string;
  email?: string | undefined;
  mobile?: string | undefined;
  temporaryPassword: string;
  roleCodes: string[];
  siteIds: string[];
}

export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: CreateUserInput) =>
      (
        await apiClient.post<AdminUser>('/users', {
          username: input.username,
          fullName: input.fullName,
          email: input.email || undefined,
          mobile: input.mobile || undefined,
          temporaryPassword: input.temporaryPassword,
          roleCodes: input.roleCodes,
          siteIds: input.siteIds,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

export interface UpdateUserInput {
  id: string;
  fullName: string;
  email?: string | undefined;
  mobile?: string | undefined;
  version: number;
  /** Sent only when changed — roles and sites are separate endpoints with their own rules. */
  roleCodes?: string[] | undefined;
  siteIds?: string[] | undefined;
}

/**
 * Profile, roles and site postings are three server calls. They run in sequence rather than
 * in parallel: roles decide whether site postings mean anything, and a half-applied edit is
 * easier to read when the failure is the last thing that ran.
 */
export function useUpdateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateUserInput) => {
      const { data } = await apiClient.put<AdminUser>(`/users/${input.id}`, {
        fullName: input.fullName,
        email: input.email || undefined,
        mobile: input.mobile || undefined,
        version: input.version,
      });
      if (input.roleCodes) {
        await apiClient.put(`/users/${input.id}/roles`, { roleCodes: input.roleCodes });
      }
      if (input.siteIds) {
        await apiClient.put(`/users/${input.id}/sites`, { siteIds: input.siteIds });
      }
      return data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

export function useUpdateUserStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, active }: { id: string; active: boolean }) =>
      (await apiClient.patch<AdminUser>(`/users/${id}/status`, { active })).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

/**
 * Hands the member a new password and forces them to replace it at next sign-in. The value
 * is never read back from the server, so the dialog that sends it is the only place it can
 * be copied from.
 */
export function useResetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, temporaryPassword }: { id: string; temporaryPassword: string }) => {
      await apiClient.post(`/users/${id}/password/reset`, { temporaryPassword });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

export function useAdminSites(projectId: string) {
  return useQuery({
    queryKey: adminKeys.sites(projectId),
    queryFn: async () =>
      (await apiClient.get<AdminSite[]>('/sites', { params: { projectId: projectId || undefined } }))
        .data,
  });
}

export function useProjects() {
  return useQuery({
    queryKey: adminKeys.projects,
    queryFn: async () =>
      (await apiClient.get<PageResponse<AdminProject>>('/projects', { params: { size: PAGE_SIZE } }))
        .data.content,
    staleTime: 15 * 60_000,
  });
}

export interface ProjectInput {
  code: string;
  name: string;
  clientDepartment?: string | undefined;
  agreementNo?: string | undefined;
  nitNumber?: string | undefined;
  tenderReference?: string | undefined;
  contractValue?: number | undefined;
  budgetAmount?: number | undefined;
  startDate?: string | undefined;
  expectedCompletionDate?: string | undefined;
  actualCompletionDate?: string | undefined;
  projectManagerId?: string | undefined;
  status?: ProjectStatus | undefined;
  description?: string | undefined;
}

export function useCreateProject() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: ProjectInput) =>
      (await apiClient.post<AdminProject>('/projects', input)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.projects });
    },
  });
}

export function useUpdateProject() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: ProjectInput & { id: string; version: number }) =>
      // The code is fixed at creation, so it is not in the update body at all.
      (await apiClient.put<AdminProject>(`/projects/${input.id}`, { ...input, code: undefined }))
        .data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.projects });
    },
  });
}

export interface SiteInput {
  projectId: string;
  code: string;
  name: string;
  address?: string | undefined;
  siteEngineerId?: string | undefined;
  supervisorId?: string | undefined;
  startDate?: string | undefined;
  standardShiftHours: number;
  monthlyWageDays: number;
  status?: SiteStatus | undefined;
}

export function useCreateSite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: SiteInput) =>
      (
        await apiClient.post<AdminSite>('/sites', {
          ...input,
          address: input.address || undefined,
          siteEngineerId: input.siteEngineerId || undefined,
          supervisorId: input.supervisorId || undefined,
          startDate: input.startDate || undefined,
        })
      ).data,
    onSuccess: () => {
      // A new posting changes what the named engineer and supervisor can reach.
      void queryClient.invalidateQueries({ queryKey: adminKeys.allSites });
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

export function useUpdateSite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: SiteInput & { id: string; version: number }) =>
      (
        await apiClient.put<AdminSite>(`/sites/${input.id}`, {
          name: input.name,
          address: input.address || undefined,
          siteEngineerId: input.siteEngineerId || undefined,
          supervisorId: input.supervisorId || undefined,
          status: input.status,
          startDate: input.startDate || undefined,
          standardShiftHours: input.standardShiftHours,
          monthlyWageDays: input.monthlyWageDays,
          version: input.version,
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.allSites });
      void queryClient.invalidateQueries({ queryKey: adminKeys.allUsers });
    },
  });
}

// --------------------------------------------------------------------------- NIT import

/**
 * Reads an uploaded tender notice and returns what it appears to contain.
 *
 * <p>Nothing about the project is saved by this call. The PDF itself is stored, so the
 * confirm step does not push fifteen megabytes over a site connection a second time; until
 * the import is confirmed that upload is unowned, and {@link useDiscardNitUpload} throws it
 * away if the user backs out.</p>
 */
export function useParseNit() {
  return useMutation({
    mutationFn: async (file: File) => {
      const form = new FormData();
      form.append('file', file, file.name);
      return (
        await apiClient.post<NitPreview>('/nit-imports/preview', form, {
          // apiClient sends JSON by default; a multipart body needs its own boundary.
          headers: { 'Content-Type': 'multipart/form-data' },
        })
      ).data;
    },
  });
}

export interface NitImportInput {
  attachmentId: string;
  pageCount: number;
  project: ProjectInput;
  fields: NitFields;
  boqLines: ConfirmedBoqLine[];
  warnings: string[];
}

/** Creates the project, its BOQ and its tender record from the reviewed preview. */
export function useCreateProjectFromNit() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: NitImportInput) =>
      (
        await apiClient.post<{
          project: AdminProject;
          nitDocumentId: string;
          boqLineCount: number;
          boqValue: number;
        }>('/nit-imports', input)
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.projects });
    },
  });
}

/**
 * Throws away a parsed upload the user decided not to use.
 *
 * <p>Failure is deliberately swallowed: the user has already closed the dialog and cannot
 * act on an error about a file they abandoned. An orphan in object storage is a housekeeping
 * problem, not theirs.</p>
 */
export function useDiscardNitUpload() {
  return useMutation({
    mutationFn: async (attachmentId: string) => {
      try {
        await apiClient.delete(`/attachments/${attachmentId}`);
      } catch {
        // Nothing useful to say to somebody who has moved on.
      }
    },
  });
}

// ------------------------------------------------------------------- project detail

export function useProject(id: string | undefined) {
  return useQuery({
    queryKey: adminKeys.project(id ?? ''),
    queryFn: async () => (await apiClient.get<AdminProject>(`/projects/${id}`)).data,
    enabled: !!id,
  });
}

/** The whole schedule for one project. Sorted by the server; a tender has a running order. */
export function useBoqItems(projectId: string | undefined) {
  return useQuery({
    queryKey: adminKeys.boqItems(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<BoqItem[]>('/boq-items', { params: { projectId } })).data,
    enabled: !!projectId,
  });
}

/**
 * Units, for turning a BOQ line's unitId into something readable.
 *
 * <p>Master data changes about once a quarter, so this is cached for the session rather
 * than refetched per screen.</p>
 */
export function useUnits() {
  return useQuery({
    queryKey: adminKeys.units,
    queryFn: async () => (await apiClient.get<UnitOption[]>('/units')).data,
    staleTime: 60 * 60_000,
  });
}

/**
 * The tender a project came from, if it was imported from one.
 *
 * <p>A project typed in by hand has no NIT document, and the endpoint 404s. That is an
 * ordinary answer rather than a failure, so it is not retried and the page simply omits
 * the tender section.</p>
 */
export function useNitDocument(projectId: string | undefined) {
  return useQuery({
    queryKey: adminKeys.nitDocument(projectId ?? ''),
    queryFn: async () =>
      (await apiClient.get<NitDocument>(`/nit-imports/projects/${projectId}`)).data,
    enabled: !!projectId,
    retry: false,
  });
}

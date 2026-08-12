import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type { EmploymentType, SalaryRevision, StaffDashboard, StaffProfile } from './types';

export const staffKeys = {
  all: ['staff'] as const,
  list: ['staff', 'list'] as const,
  dashboard: ['staff', 'dashboard'] as const,
  profile: (userId: string) => ['staff', 'profile', userId] as const,
  salary: (userId: string) => ['staff', 'salary', userId] as const,
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

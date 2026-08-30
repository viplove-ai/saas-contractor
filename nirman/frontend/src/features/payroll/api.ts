import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import { downloadBlob } from '../staff/api';
import type { PayrollRun, PayrollRunSummary, Payslip, PayslipInput } from './types';

export const payrollKeys = {
  all: ['payroll'] as const,
  runs: ['payroll', 'runs'] as const,
  run: (id: string) => ['payroll', 'run', id] as const,
};

export function usePayrollRuns() {
  return useQuery({
    queryKey: payrollKeys.runs,
    queryFn: async () => (await apiClient.get<PayrollRunSummary[]>('/payroll/runs')).data,
  });
}

export function usePayrollRun(id: string | undefined) {
  return useQuery({
    queryKey: payrollKeys.run(id ?? ''),
    queryFn: async () => (await apiClient.get<PayrollRun>(`/payroll/runs/${id}`)).data,
    enabled: Boolean(id),
  });
}

export interface OpenRunInput {
  periodMonth: string;
  payableDays?: number;
  notes?: string;
}

export function useOpenPayrollRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: OpenRunInput) =>
      (await apiClient.post<PayrollRun>('/payroll/runs', body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

/**
 * Draws the month again against today's staff records.
 *
 * <p>Adds anybody now drawable and rebuilds the structure half of every slip; the typed
 * figures — days, overtime, tax, recoveries — are carried across on the server, because a
 * redraw that reset them would not be used, and the month would then go out on a figure
 * everybody knew was wrong.</p>
 */
export function useRedrawPayrollRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<PayrollRun>(`/payroll/runs/${id}/redraw`)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

export function useUpdatePayrollRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: { id: string; payableDays: number; notes?: string; version: number }) =>
      (await apiClient.put<PayrollRun>(`/payroll/runs/${id}`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

/** Once, and with no way back: those payslips exist. */
export function useFinalisePayrollRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      (await apiClient.post<PayrollRun>(`/payroll/runs/${id}/finalise`)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

export function useDeletePayrollRun() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/payroll/runs/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

export function useUpdatePayslip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: PayslipInput & { id: string }) =>
      (await apiClient.put<Payslip>(`/payroll/payslips/${id}`, body)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

export function useRemovePayslip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/payroll/payslips/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: payrollKeys.all });
    },
  });
}

/**
 * The printed documents.
 *
 * <p>Three, because they are read by different people: the register is the office's sheet and
 * carries the employer's own contributions, the payslips are what is handed over and do not,
 * and one slip on its own is for the man who lost his.</p>
 */
export function useDownloadPayrollPdf() {
  return useMutation({
    mutationFn: async ({ path, fileName }: { path: string; fileName: string }) => {
      const response = await apiClient.get(path, { responseType: 'blob' });
      downloadBlob(response.data as Blob, fileName);
    },
  });
}

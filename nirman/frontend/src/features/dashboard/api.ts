import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type {
  CompanyDashboard,
  DataQualityDashboard,
  Site,
  SiteDashboard,
} from './types';

export const dashboardKeys = {
  sites: ['sites'] as const,
  company: (from: string, to: string) => ['dashboard', 'company', from, to] as const,
  site: (siteId: string, from: string, to: string) =>
    ['dashboard', 'site', siteId, from, to] as const,
  quality: (siteId: string, from: string, to: string) =>
    ['dashboard', 'quality', siteId, from, to] as const,
};

export function useSites() {
  return useQuery({
    queryKey: dashboardKeys.sites,
    queryFn: async () => (await apiClient.get<Site[]>('/sites')).data,
    staleTime: 15 * 60_000,
  });
}

export function useCompanyDashboard(from: string, to: string) {
  return useQuery({
    queryKey: dashboardKeys.company(from, to),
    queryFn: async () =>
      (await apiClient.get<CompanyDashboard>('/dashboard/admin', { params: { from, to } })).data,
  });
}

/**
 * @param enabled pass false where the caller may not hold {@code dashboard:site} — a
 *                supervisor does not, and firing the request anyway would answer his
 *                landing screen with a 403 he can do nothing about.
 */
export function useSiteDashboard(
  siteId: string | undefined,
  from: string,
  to: string,
  enabled = true,
) {
  return useQuery({
    queryKey: dashboardKeys.site(siteId ?? '', from, to),
    queryFn: async () =>
      (await apiClient.get<SiteDashboard>(`/dashboard/site/${siteId}`, { params: { from, to } }))
        .data,
    enabled: Boolean(siteId) && enabled,
  });
}

export function useDataQuality(siteId: string | undefined, from: string, to: string) {
  return useQuery({
    queryKey: dashboardKeys.quality(siteId ?? '', from, to),
    queryFn: async () =>
      (
        await apiClient.get<DataQualityDashboard>('/dashboard/data-quality', {
          params: { siteId, from, to },
        })
      ).data,
  });
}

/** The month to date — the window somebody opening a dashboard almost always means. */
export function monthToDate(): { from: string; to: string } {
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth(), 1);
  return { from: iso(first), to: iso(now) };
}

export function iso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

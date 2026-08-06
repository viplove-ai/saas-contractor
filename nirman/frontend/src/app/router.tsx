import { createBrowserRouter, Navigate } from 'react-router-dom';
import { RequireAuth } from '../features/auth/AuthContext';
import { SyncPage } from '../features/sync/SyncPage';

/**
 * Route table. Everything under '/' sits behind {@link RequireAuth}: an anonymous visit
 * to any screen lands on /login and returns to the requested path after sign-in.
 *
 * Two shells: SupervisorShell for phone-first task entry, DeskShell for desk roles.
 * The signed-in user's role decides which one the index route resolves to (Phase 3, when
 * the first supervisor task screens exist).
 */
export const router = createBrowserRouter([
  {
    path: '/login',
    lazy: async () => {
      const { LoginPage } = await import('../features/auth/LoginPage');
      return { Component: LoginPage };
    },
  },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        lazy: async () => {
          const { RootLayout } = await import('./RootLayout');
          return { Component: RootLayout };
        },
        children: [
          { index: true, element: <Navigate to="/today" replace /> },
          {
            path: 'today',
            lazy: async () => {
              const { TodayPage } = await import('../features/today/TodayPage');
              return { Component: TodayPage };
            },
          },
          /*
            The one screen in the table that is not split out, because it is the only one
            whose whole purpose is to work with no signal. Every other route fetches its
            chunk when it is opened; the offline banner's Review link would then lead to a
            network request that cannot succeed — an alarm pointing at a door that does not
            open. The service worker precaches the chunks and covers this from the second
            visit onwards, but the first spell with no signal can arrive before it has taken
            control, and this screen is exactly the one that must not depend on that.
          */
          { path: 'sync', element: <SyncPage /> },
          {
            path: 'home',
            lazy: async () => {
              const { HomePage } = await import('../features/home/HomePage');
              return { Component: HomePage };
            },
          },
          {
            path: 'attendance/mark',
            lazy: async () => {
              const { MarkAttendancePage } = await import(
                '../features/attendance/MarkAttendancePage'
              );
              return { Component: MarkAttendancePage };
            },
          },
          {
            path: 'attendance/verify',
            lazy: async () => {
              const { VerifyAttendancePage } = await import(
                '../features/attendance/VerifyAttendancePage'
              );
              return { Component: VerifyAttendancePage };
            },
          },
          {
            path: 'inventory/receive',
            lazy: async () => {
              const { ReceiveMaterialPage } = await import(
                '../features/inventory/ReceiveMaterialPage'
              );
              return { Component: ReceiveMaterialPage };
            },
          },
          {
            path: 'inventory/issue',
            lazy: async () => {
              const { IssueMaterialPage } = await import(
                '../features/inventory/IssueMaterialPage'
              );
              return { Component: IssueMaterialPage };
            },
          },
          {
            path: 'inventory/stock',
            lazy: async () => {
              const { StockPage } = await import('../features/inventory/StockPage');
              return { Component: StockPage };
            },
          },
          {
            path: 'expenses/new',
            lazy: async () => {
              const { AddExpensePage } = await import('../features/expenses/AddExpensePage');
              return { Component: AddExpensePage };
            },
          },
          {
            path: 'approvals',
            lazy: async () => {
              const { ApprovalsPage } = await import('../features/expenses/ApprovalsPage');
              return { Component: ApprovalsPage };
            },
          },
          {
            path: 'dpr/new',
            lazy: async () => {
              const { DprWizardPage } = await import('../features/dpr/DprWizardPage');
              return { Component: DprWizardPage };
            },
          },
          {
            path: 'dprs',
            lazy: async () => {
              const { DprListPage } = await import('../features/dpr/DprListPage');
              return { Component: DprListPage };
            },
          },
          {
            path: 'dashboard',
            lazy: async () => {
              const { CompanyDashboardPage } = await import(
                '../features/dashboard/CompanyDashboardPage'
              );
              return { Component: CompanyDashboardPage };
            },
          },
          {
            // Without a site id the screen resolves the first one the caller can see.
            path: 'dashboard/site/:siteId?',
            lazy: async () => {
              const { SiteDashboardPage } = await import(
                '../features/dashboard/SiteDashboardPage'
              );
              return { Component: SiteDashboardPage };
            },
          },
          {
            path: 'dashboard/quality',
            lazy: async () => {
              const { DataQualityPage } = await import('../features/dashboard/DataQualityPage');
              return { Component: DataQualityPage };
            },
          },
          {
            path: 'workers',
            lazy: async () => {
              const { WorkersPage } = await import('../features/labour/WorkersPage');
              return { Component: WorkersPage };
            },
          },
          {
            // Also where RequireAuth parks anyone still on an admin-issued password.
            path: 'profile',
            lazy: async () => {
              const { ProfilePage } = await import('../features/profile/ProfilePage');
              return { Component: ProfilePage };
            },
          },
          {
            path: 'users',
            lazy: async () => {
              const { UsersPage } = await import('../features/admin/UsersPage');
              return { Component: UsersPage };
            },
          },
          {
            path: 'projects',
            lazy: async () => {
              const { ProjectsPage } = await import('../features/admin/ProjectsPage');
              return { Component: ProjectsPage };
            },
          },
          {
            path: 'projects/:projectId',
            lazy: async () => {
              const { ProjectDetailPage } = await import(
                '../features/admin/ProjectDetailPage'
              );
              return { Component: ProjectDetailPage };
            },
          },
          {
            path: 'sites',
            lazy: async () => {
              const { SitesPage } = await import('../features/admin/SitesPage');
              return { Component: SitesPage };
            },
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/today" replace /> },
]);

import type { ComponentType } from 'react';
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { RequireAuth } from '../features/auth/AuthContext';
import { SyncPage } from '../features/sync/SyncPage';
import { RouteError } from './RouteError';

/**
 * One code-split screen, with the boundary that catches it failing to download.
 *
 * <p>The boundary goes on the leaf and not on the shell above it, which is the difference
 * between a screen that will not open and an app that will not open. React Router replaces
 * the element of whichever route owns the boundary — put it on the layout and a missing
 * chunk takes the navigation, the unsent-records banner and the way to the sync screen down
 * with it, at the exact moment the supervisor most needs all three.</p>
 */
function screen(path: string, load: () => Promise<{ Component: ComponentType }>): RouteObject {
  return { path, lazy: load, errorElement: <RouteError /> };
}

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
    // Its own boundary, because it sits outside the shell — and it is where a phone with no
    // signal and no session lands. The one thing worse than a sign-in screen that cannot be
    // satisfied is a blank one.
    errorElement: <RouteError />,
    lazy: async () => ({ Component: (await import('../features/auth/LoginPage')).LoginPage }),
  },
  {
    path: '/',
    element: <RequireAuth />,
    errorElement: <RouteError />,
    children: [
      {
        lazy: async () => ({ Component: (await import('./RootLayout')).RootLayout }),
        children: [
          { index: true, element: <Navigate to="/today" replace /> },
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
          screen('today', async () => ({
            Component: (await import('../features/today/TodayPage')).TodayPage,
          })),
          screen('home', async () => ({
            Component: (await import('../features/home/HomePage')).HomePage,
          })),
          screen('attendance/mark', async () => ({
            Component: (await import('../features/attendance/MarkAttendancePage'))
              .MarkAttendancePage,
          })),
          screen('attendance/verify', async () => ({
            Component: (await import('../features/attendance/VerifyAttendancePage'))
              .VerifyAttendancePage,
          })),
          screen('inventory/receive', async () => ({
            Component: (await import('../features/inventory/ReceiveMaterialPage'))
              .ReceiveMaterialPage,
          })),
          screen('inventory/issue', async () => ({
            Component: (await import('../features/inventory/IssueMaterialPage')).IssueMaterialPage,
          })),
          screen('inventory/stock', async () => ({
            Component: (await import('../features/inventory/StockPage')).StockPage,
          })),
          screen('expenses/new', async () => ({
            Component: (await import('../features/expenses/AddExpensePage')).AddExpensePage,
          })),
          screen('approvals', async () => ({
            Component: (await import('../features/expenses/ApprovalsPage')).ApprovalsPage,
          })),
          // Approvals is a queue; this is the ledger the queue empties into. Different
          // question, different screen: "what is waiting on me" against "what did this month
          // cost, and how much of it was the company's".
          screen('payments', async () => ({
            Component: (await import('../features/expenses/PaymentsRegisterPage'))
              .PaymentsRegisterPage,
          })),
          // The other half of the payments register: what left the books and is coming back.
          // Its own screen because the question is asked months later and usually by somebody
          // else — "what are we holding out with the electricity board" is a treasury question
          // that happens to be answered out of the expense register.
          screen('deposits', async () => ({
            Component: (await import('../features/expenses/DepositsPage')).DepositsPage,
          })),
          screen('dpr/new', async () => ({
            Component: (await import('../features/dpr/DprWizardPage')).DprWizardPage,
          })),
          // The same screen, opened on a report already started. The wizard stands itself on
          // that report's day and asks whether to carry on with it or write it again.
          screen('dpr/:id', async () => ({
            Component: (await import('../features/dpr/DprWizardPage')).DprWizardPage,
          })),
          screen('dprs', async () => ({
            Component: (await import('../features/dpr/DprListPage')).DprListPage,
          })),
          // Billing. Every screen behind billing:* permissions, which V46 grants to the
          // administrator and the engineer only — an account without them never sees the tab.
          screen('billing', async () => ({
            Component: (await import('../features/billing/BillingProjectsPage'))
              .BillingProjectsPage,
          })),
          screen('billing/:projectId/sheets', async () => ({
            Component: (await import('../features/billing/SheetRegisterPage')).SheetRegisterPage,
          })),
          screen('billing/:projectId/bills', async () => ({
            Component: (await import('../features/billing/BillsPage')).BillsPage,
          })),
          // The shelf. Reading it goes with reading a bill; changing it is dsr:manage, which
          // the server enforces — this route only decides where the screen lives.
          screen('billing/vault', async () => ({
            Component: (await import('../features/billing/VaultPage')).VaultPage,
          })),
          screen('billing/sheets/new', async () => ({
            Component: (await import('../features/billing/MeasurementSheetPage'))
              .MeasurementSheetPage,
          })),
          // The same screen opened on a sheet already recorded; signed and billed ones open
          // read-only, because a correction is a new sheet and never an edit.
          screen('billing/sheets/:id', async () => ({
            Component: (await import('../features/billing/MeasurementSheetPage'))
              .MeasurementSheetPage,
          })),
          screen('dashboard', async () => ({
            Component: (await import('../features/dashboard/CompanyDashboardPage'))
              .CompanyDashboardPage,
          })),
          // Without a site id the screen resolves the first one the caller can see.
          screen('dashboard/site/:siteId?', async () => ({
            Component: (await import('../features/dashboard/SiteDashboardPage')).SiteDashboardPage,
          })),
          screen('dashboard/quality', async () => ({
            Component: (await import('../features/dashboard/DataQualityPage')).DataQualityPage,
          })),
          screen('workers', async () => ({
            Component: (await import('../features/labour/WorkersPage')).WorkersPage,
          })),
          // Also where RequireAuth parks anyone still on an admin-issued password.
          screen('profile', async () => ({
            Component: (await import('../features/profile/ProfilePage')).ProfilePage,
          })),
          screen('users', async () => ({
            Component: (await import('../features/admin/UsersPage')).UsersPage,
          })),
          screen('projects', async () => ({
            Component: (await import('../features/admin/ProjectsPage')).ProjectsPage,
          })),
          screen('projects/:projectId', async () => ({
            Component: (await import('../features/admin/ProjectDetailPage')).ProjectDetailPage,
          })),
          screen('projects/:projectId/planning', async () => ({
            Component: (await import('../features/planning/PlanningPage')).PlanningPage,
          })),
          // One contract's deposits. Its own route rather than a panel on the project page,
          // for the reason planning has one: features do not import from one another.
          screen('projects/:projectId/securities', async () => ({
            Component: (await import('../features/treasury/ProjectSecuritiesPage'))
              .ProjectSecuritiesPage,
          })),
          screen('treasury', async () => ({
            Component: (await import('../features/treasury/TreasuryDashboardPage'))
              .TreasuryDashboardPage,
          })),
          // The bank's own view of the same money: every certificate the company holds, pledged
          // to a contract or to nothing. A tab beside the dashboard rather than a panel on it,
          // because "what have we got" is a different question from "what has this job locked
          // up" and the two are read by the same person at different moments.
          screen('treasury/fdrs', async () => ({
            Component: (await import('../features/treasury/FdrRegisterPage')).FdrRegisterPage,
          })),
          // The third treasury question, and the one nothing answered: where is the company's
          // cash standing right now. Some of it is in the pocket of a supervisor on a site, and
          // the register that holds it has existed since V1 with no screen onto it.
          screen('treasury/floats', async () => ({
            Component: (await import('../features/treasury/SiteFloatsPage')).SiteFloatsPage,
          })),
          screen('sites', async () => ({
            Component: (await import('../features/admin/SitesPage')).SitesPage,
          })),
          screen('stores', async () => ({
            Component: (await import('../features/admin/StoresPage')).StoresPage,
          })),
          screen('staff', async () => ({
            Component: (await import('../features/staff/StaffPage')).StaffPage,
          })),
          screen('vendors', async () => ({
            Component: (await import('../features/vendors/VendorsPage')).VendorsPage,
          })),
          screen('vendors/:vendorId', async () => ({
            Component: (await import('../features/vendors/VendorDetailPage')).VendorDetailPage,
          })),
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/today" replace /> },
]);

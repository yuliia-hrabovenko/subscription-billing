import { Route, Routes } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { DashboardPage } from '../features/subscription/DashboardPage';
import { SignupPage } from '../features/subscription/SignupPage';
import { InvoiceDetailPage } from '../features/invoices/InvoiceDetailPage';
import { InvoicesPage } from '../features/invoices/InvoicesPage';
import { PlansPage } from '../features/plans/PlansPage';
import { AdminRoutes } from './AdminRoutes';
import { RequireAuth } from './RequireAuth';
import { SessionExpiredPage } from './SessionExpiredPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="admin/*" element={<AdminRoutes />} />
      <Route element={<AppLayout />}>
        <Route index element={<PlansPage />} />
        <Route path="signup" element={<SignupPage />} />
        <Route path="session-expired" element={<SessionExpiredPage />} />
        <Route
          path="dashboard"
          element={
            <RequireAuth>
              <DashboardPage />
            </RequireAuth>
          }
        />
        <Route
          path="dashboard/invoices"
          element={
            <RequireAuth>
              <InvoicesPage />
            </RequireAuth>
          }
        />
        <Route
          path="dashboard/invoices/:id"
          element={
            <RequireAuth>
              <InvoiceDetailPage />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  );
}

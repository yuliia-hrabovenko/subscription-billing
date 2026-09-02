import { Route, Routes } from 'react-router-dom';
import { AdminLayout } from '../components/AdminLayout';
import { AdminLoginPage } from '../features/admin/auth/AdminLoginPage';
import { AdminCustomerDetailPage } from '../features/admin/customers/AdminCustomerDetailPage';
import { AdminCustomersPage } from '../features/admin/customers/AdminCustomersPage';
import { AdminInvoiceDetailPage } from '../features/admin/invoices/AdminInvoiceDetailPage';
import { AdminOverviewPage } from '../features/admin/overview/AdminOverviewPage';
import { AdminPlanDetailPage } from '../features/admin/plans/AdminPlanDetailPage';
import { AdminPlansPage } from '../features/admin/plans/AdminPlansPage';
import { AdminSubscriptionDetailPage } from '../features/admin/subscriptions/AdminSubscriptionDetailPage';
import { RequireAdminAuth } from './RequireAdminAuth';

export function AdminRoutes() {
  return (
    <Routes>
      <Route path="login" element={<AdminLoginPage />} />
      <Route
        element={
          <RequireAdminAuth>
            <AdminLayout />
          </RequireAdminAuth>
        }
      >
        <Route index element={<AdminOverviewPage />} />
        <Route path="customers" element={<AdminCustomersPage />} />
        <Route path="customers/:id" element={<AdminCustomerDetailPage />} />
        <Route path="subscriptions/:id" element={<AdminSubscriptionDetailPage />} />
        <Route path="invoices/:id" element={<AdminInvoiceDetailPage />} />
        <Route path="plans" element={<AdminPlansPage />} />
        <Route path="plans/:id" element={<AdminPlanDetailPage />} />
      </Route>
    </Routes>
  );
}

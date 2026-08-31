import { Typography } from '@mui/material';
import { Route, Routes } from 'react-router-dom';
import { AdminLayout } from '../components/AdminLayout';
import { AdminLoginPage } from '../features/admin/auth/AdminLoginPage';
import { AdminPlanDetailPage } from '../features/admin/plans/AdminPlanDetailPage';
import { AdminPlansPage } from '../features/admin/plans/AdminPlansPage';
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
        {/* Replaced by a real Overview page in a later step. */}
        <Route index element={<Typography>Overview coming soon</Typography>} />
        <Route path="plans" element={<AdminPlansPage />} />
        <Route path="plans/:id" element={<AdminPlanDetailPage />} />
      </Route>
    </Routes>
  );
}

import { Typography } from '@mui/material';
import { Route, Routes } from 'react-router-dom';
import { AdminLayout } from '../components/AdminLayout';
import { AdminLoginPage } from '../features/admin/auth/AdminLoginPage';
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
      </Route>
    </Routes>
  );
}

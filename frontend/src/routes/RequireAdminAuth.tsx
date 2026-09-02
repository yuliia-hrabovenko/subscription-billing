import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAdminAuth } from '../auth/useAdminAuth';

export function RequireAdminAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAdminAuth();

  if (!isAuthenticated) {
    return <Navigate to="/admin/login" replace />;
  }

  return children;
}

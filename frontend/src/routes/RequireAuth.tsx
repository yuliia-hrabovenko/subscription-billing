import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/session-expired" replace />;
  }

  return children;
}

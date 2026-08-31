import { createContext } from 'react';
import type { StoredAdminSession } from './adminSessionStore';

export interface AdminAuthContextValue {
  session: StoredAdminSession | null;
  isAuthenticated: boolean;
  signIn: (accessToken: string) => void;
  signOut: () => void;
}

export const AdminAuthContext = createContext<AdminAuthContextValue | undefined>(undefined);

import { useEffect, useState, type ReactNode } from 'react';
import { adminSessionStore, type StoredAdminSession } from './adminSessionStore';
import { AdminAuthContext, type AdminAuthContextValue } from './adminContext';

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<StoredAdminSession | null>(() => adminSessionStore.get());

  // Reacts to the API client's auth middleware clearing the session on a 401, not just
  // to signOut() called from here — both funnel through the same store.
  useEffect(() => adminSessionStore.subscribe(setSession), []);

  const value: AdminAuthContextValue = {
    session,
    isAuthenticated: session !== null,
    signIn: (accessToken) => adminSessionStore.set(accessToken),
    signOut: () => adminSessionStore.clear(),
  };

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>;
}

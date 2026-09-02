import { useEffect, useState, type ReactNode } from 'react';
import { AuthContext, type AuthContextValue } from './context';
import { sessionStore, type StoredSession } from './sessionStore';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<StoredSession | null>(() => sessionStore.get());

  // Reacts to the API client's auth middleware clearing the session on a 401, not just
  // to signOut() called from here — both funnel through the same store.
  useEffect(() => sessionStore.subscribe(setSession), []);

  const value: AuthContextValue = {
    session,
    isAuthenticated: session !== null,
    signIn: (accessToken, subscriptionId) => sessionStore.set(accessToken, subscriptionId),
    signOut: () => sessionStore.clear(),
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

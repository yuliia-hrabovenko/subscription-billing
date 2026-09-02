import { createContext } from 'react';
import type { StoredSession } from './sessionStore';

export interface AuthContextValue {
  session: StoredSession | null;
  isAuthenticated: boolean;
  signIn: (accessToken: string, subscriptionId: string | null) => void;
  signOut: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

import { createContext } from 'react';
import type { StoredSession } from './sessionStore';

export interface AuthContextValue {
  session: StoredSession | null;
  isAuthenticated: boolean;
  signIn: (accessToken: string, subscriptionId: string) => void;
  signOut: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

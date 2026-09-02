const STORAGE_KEY = 'subscription-billing.admin-session';

export interface StoredAdminSession {
  accessToken: string;
  expiresAt: number;
  username: string;
}

type Listener = (session: StoredAdminSession | null) => void;

function decodeClaims(token: string): { exp: number; sub: string } {
  const payload = token.split('.')[1];
  if (!payload) {
    throw new Error('Malformed access token: missing payload segment');
  }
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const claims = JSON.parse(atob(base64)) as { exp?: unknown; sub?: unknown };
  if (typeof claims.exp !== 'number') {
    throw new Error('Malformed access token: missing exp claim');
  }
  if (typeof claims.sub !== 'string') {
    throw new Error('Malformed access token: missing sub claim');
  }
  return { exp: claims.exp, sub: claims.sub };
}

/**
 * Mirrors sessionStore.ts but for the ADMIN persona: a separate localStorage key, a
 * separate listener set, and no subscriptionId to remember. AdminLoginResponse only
 * returns an accessToken (no username field), so username is read back out of the
 * token's own `sub` claim — the same claim AdminTokenIssuer sets it from server-side —
 * rather than trusting whatever the login form happened to have typed in.
 */
class AdminSessionStore {
  private listeners = new Set<Listener>();

  get(): StoredAdminSession | null {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    let stored: StoredAdminSession;
    try {
      stored = JSON.parse(raw) as StoredAdminSession;
    } catch {
      this.clear();
      return null;
    }
    if (Date.now() >= stored.expiresAt) {
      this.clear();
      return null;
    }
    return stored;
  }

  set(accessToken: string): void {
    const { exp, sub } = decodeClaims(accessToken);
    const stored: StoredAdminSession = {
      accessToken,
      username: sub,
      expiresAt: exp * 1000,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    this.notify(stored);
  }

  clear(): void {
    localStorage.removeItem(STORAGE_KEY);
    this.notify(null);
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notify(session: StoredAdminSession | null): void {
    for (const listener of this.listeners) {
      listener(session);
    }
  }
}

export const adminSessionStore = new AdminSessionStore();

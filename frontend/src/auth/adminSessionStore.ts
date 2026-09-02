const STORAGE_KEY = 'subscription-billing.admin-session';

export interface StoredAdminSession {
  accessToken: string;
  expiresAt: number;
  username: string;
}

type Listener = (session: StoredAdminSession | null) => void;

function decodeClaims(token: string): { exp: number; username: string } {
  const payload = token.split('.')[1];
  if (!payload) {
    throw new Error('Malformed access token: missing payload segment');
  }
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const claims = JSON.parse(atob(base64)) as {
    exp?: unknown;
    sub?: unknown;
    preferred_username?: unknown;
  };
  if (typeof claims.exp !== 'number') {
    throw new Error('Malformed access token: missing exp claim');
  }
  if (typeof claims.sub !== 'string') {
    throw new Error('Malformed access token: missing sub claim');
  }
  const username = typeof claims.preferred_username === 'string' ? claims.preferred_username : claims.sub;
  return { exp: claims.exp, username };
}

/**
 * Mirrors sessionStore.ts but for the ADMIN persona: a separate localStorage key, a
 * separate listener set, and no subscriptionId to remember. The access token is now
 * Keycloak-issued, so username is read from its `preferred_username` claim
 * rather than `sub` — Keycloak's `sub` is an opaque user id, not a human-readable name.
 * Falls back to `sub` if `preferred_username` is absent, so a malformed/non-Keycloak
 * token still decodes to *something* rather than failing session setup outright.
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
    const { exp, username } = decodeClaims(accessToken);
    const stored: StoredAdminSession = {
      accessToken,
      username,
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

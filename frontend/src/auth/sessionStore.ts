const STORAGE_KEY = 'subscription-billing.session';

export interface StoredSession {
  accessToken: string;
  expiresAt: number;
  subscriptionId: string;
}

type Listener = (session: StoredSession | null) => void;

function decodeExpiryMillis(token: string): number {
  const payload = token.split('.')[1];
  if (!payload) {
    throw new Error('Malformed access token: missing payload segment');
  }
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const claims = JSON.parse(atob(base64)) as { exp?: unknown };
  if (typeof claims.exp !== 'number') {
    throw new Error('Malformed access token: missing exp claim');
  }
  return claims.exp * 1000;
}

/**
 * Plain module (not a React context) so the API client's auth middleware — which runs
 * outside the component tree — can read the current session without prop drilling.
 * Holds `subscriptionId` alongside the token because there is no "list my subscriptions"
 * endpoint: signup (POST /subscriptions) is the only place a subscriptionId is ever
 * handed back, so it has to be remembered client-side for every later
 * GET /subscriptions/{id} call. There is also no login/refresh endpoint (ADR-0003), so
 * once a stored token expires there is nothing to silently renew: callers surface that
 * as a signed-out state instead.
 */
class SessionStore {
  private listeners = new Set<Listener>();

  get(): StoredSession | null {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    let stored: StoredSession;
    try {
      stored = JSON.parse(raw) as StoredSession;
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

  set(accessToken: string, subscriptionId: string): void {
    const stored: StoredSession = {
      accessToken,
      subscriptionId,
      expiresAt: decodeExpiryMillis(accessToken),
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

  private notify(session: StoredSession | null): void {
    for (const listener of this.listeners) {
      listener(session);
    }
  }
}

export const sessionStore = new SessionStore();

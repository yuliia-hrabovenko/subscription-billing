import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionStore } from './sessionStore';

function fakeJwt(claims: Record<string, unknown>): string {
  const base64url = (value: string) =>
    btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  return `${header}.${payload}.signature`;
}

const SUBSCRIPTION_ID = 'a1c9e2e4-6b3e-4b7a-8b8a-2f8f1e3a9b10';

describe('sessionStore', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns null when nothing is stored', () => {
    expect(sessionStore.get()).toBeNull();
  });

  it('round-trips a session that has not expired', () => {
    const token = fakeJwt({ sub: 'customer-1', exp: Math.floor(Date.now() / 1000) + 3600 });

    sessionStore.set(token, SUBSCRIPTION_ID);

    const stored = sessionStore.get();
    expect(stored?.accessToken).toBe(token);
    expect(stored?.subscriptionId).toBe(SUBSCRIPTION_ID);
  });

  it('treats an already-expired session as absent and clears it', () => {
    const token = fakeJwt({ sub: 'customer-1', exp: Math.floor(Date.now() / 1000) - 60 });
    sessionStore.set(token, SUBSCRIPTION_ID);

    expect(sessionStore.get()).toBeNull();
    expect(localStorage.getItem('subscription-billing.session')).toBeNull();
  });

  it('rejects a token with no exp claim', () => {
    const token = fakeJwt({ sub: 'customer-1' });

    expect(() => sessionStore.set(token, SUBSCRIPTION_ID)).toThrow(/exp claim/);
  });

  it('notifies subscribers on set and clear', () => {
    const listener = vi.fn();
    const unsubscribe = sessionStore.subscribe(listener);
    const token = fakeJwt({ sub: 'customer-1', exp: Math.floor(Date.now() / 1000) + 3600 });

    sessionStore.set(token, SUBSCRIPTION_ID);
    sessionStore.clear();

    expect(listener).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ accessToken: token, subscriptionId: SUBSCRIPTION_ID }),
    );
    expect(listener).toHaveBeenNthCalledWith(2, null);
    unsubscribe();
  });
});

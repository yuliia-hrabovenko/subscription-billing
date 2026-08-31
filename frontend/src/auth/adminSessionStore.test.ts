import { beforeEach, describe, expect, it, vi } from 'vitest';
import { adminSessionStore } from './adminSessionStore';

function fakeJwt(claims: Record<string, unknown>): string {
  const base64url = (value: string) =>
    btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  return `${header}.${payload}.signature`;
}

describe('adminSessionStore', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns null when nothing is stored', () => {
    expect(adminSessionStore.get()).toBeNull();
  });

  it('round-trips a session that has not expired', () => {
    const token = fakeJwt({ sub: 'admin', exp: Math.floor(Date.now() / 1000) + 3600 });

    adminSessionStore.set(token);

    const stored = adminSessionStore.get();
    expect(stored?.accessToken).toBe(token);
    expect(stored?.username).toBe('admin');
  });

  it('treats an already-expired session as absent and clears it', () => {
    const token = fakeJwt({ sub: 'admin', exp: Math.floor(Date.now() / 1000) - 60 });
    adminSessionStore.set(token);

    expect(adminSessionStore.get()).toBeNull();
    expect(localStorage.getItem('subscription-billing.admin-session')).toBeNull();
  });

  it('rejects a token with no exp claim', () => {
    const token = fakeJwt({ sub: 'admin' });

    expect(() => adminSessionStore.set(token)).toThrow(/exp claim/);
  });

  it('notifies subscribers on set and clear', () => {
    const listener = vi.fn();
    const unsubscribe = adminSessionStore.subscribe(listener);
    const token = fakeJwt({ sub: 'admin', exp: Math.floor(Date.now() / 1000) + 3600 });

    adminSessionStore.set(token);
    adminSessionStore.clear();

    expect(listener).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ accessToken: token, username: 'admin' }),
    );
    expect(listener).toHaveBeenNthCalledWith(2, null);
    unsubscribe();
  });
});

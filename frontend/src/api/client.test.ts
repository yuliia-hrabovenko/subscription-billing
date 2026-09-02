import { beforeEach, describe, expect, it } from 'vitest';
import { adminSessionStore } from '../auth/adminSessionStore';
import { sessionStore } from '../auth/sessionStore';
import { authMiddleware, baseUrl } from './client';

function fakeJwt(claims: Record<string, unknown>): string {
  const base64url = (value: string) =>
    btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  return `${header}.${payload}.signature`;
}

const FUTURE_EXP = Math.floor(Date.now() / 1000) + 3600;
const SUBSCRIPTION_ID = 'a1c9e2e4-6b3e-4b7a-8b8a-2f8f1e3a9b10';

function onRequest(url: string) {
  const request = new Request(url);
  return authMiddleware.onRequest!({
    request,
    schemaPath: '',
    params: {},
    id: 'test',
    options: {} as never,
  });
}

function onResponse(url: string, status: number) {
  const request = new Request(url);
  const response = new Response(null, { status });
  return authMiddleware.onResponse!({
    request,
    response,
    schemaPath: '',
    params: {},
    id: 'test',
    options: {} as never,
  });
}

describe('authMiddleware', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('attaches the customer token to a customer-path request', async () => {
    const token = fakeJwt({ sub: 'customer-1', exp: FUTURE_EXP });
    sessionStore.set(token, SUBSCRIPTION_ID);

    const result = await onRequest(`${baseUrl}/api/v1/subscriptions/${SUBSCRIPTION_ID}`);

    expect((result as Request).headers.get('Authorization')).toBe(`Bearer ${token}`);
  });

  it('attaches the admin token to an admin-path request', async () => {
    const token = fakeJwt({ sub: 'admin', exp: FUTURE_EXP });
    adminSessionStore.set(token);

    const result = await onRequest(`${baseUrl}/api/v1/admin/customers`);

    expect((result as Request).headers.get('Authorization')).toBe(`Bearer ${token}`);
  });

  it('does not attach the customer token to an admin-path request', async () => {
    sessionStore.set(fakeJwt({ sub: 'customer-1', exp: FUTURE_EXP }), SUBSCRIPTION_ID);

    const result = await onRequest(`${baseUrl}/api/v1/admin/customers`);

    expect((result as Request).headers.get('Authorization')).toBeNull();
  });

  it('clears only the admin session on a 401 from an admin-path request', async () => {
    sessionStore.set(fakeJwt({ sub: 'customer-1', exp: FUTURE_EXP }), SUBSCRIPTION_ID);
    adminSessionStore.set(fakeJwt({ sub: 'admin', exp: FUTURE_EXP }));

    await onResponse(`${baseUrl}/api/v1/admin/customers`, 401);

    expect(adminSessionStore.get()).toBeNull();
    expect(sessionStore.get()).not.toBeNull();
  });

  it('clears only the customer session on a 401 from a customer-path request', async () => {
    sessionStore.set(fakeJwt({ sub: 'customer-1', exp: FUTURE_EXP }), SUBSCRIPTION_ID);
    adminSessionStore.set(fakeJwt({ sub: 'admin', exp: FUTURE_EXP }));

    await onResponse(`${baseUrl}/api/v1/subscriptions/${SUBSCRIPTION_ID}`, 401);

    expect(sessionStore.get()).toBeNull();
    expect(adminSessionStore.get()).not.toBeNull();
  });
});

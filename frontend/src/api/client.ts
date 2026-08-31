import createFetchClient, { type Middleware } from 'openapi-fetch';
import createQueryClient from 'openapi-react-query';
import { adminSessionStore } from '../auth/adminSessionStore';
import { sessionStore } from '../auth/sessionStore';
import type { paths } from './generated/schema';

export const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const ADMIN_PATH_PREFIX = '/api/v1/admin';

function isAdminPath(url: string): boolean {
  return new URL(url).pathname.startsWith(ADMIN_PATH_PREFIX);
}

/**
 * Signup (POST /subscriptions) and admin login (POST /admin/login) are the only public
 * writes; every other write/read past them needs a bearer token. CUSTOMER and ADMIN are
 * separate personas with separate token stores (sessionStore vs adminSessionStore), so
 * which one this middleware reads from/clears on a 401 is decided per-request by path —
 * an admin-path request must never read or clear the customer session, and vice versa.
 * Neither persona has a refresh endpoint, so a 401 here means that persona's session is
 * over for good.
 */
export const authMiddleware: Middleware = {
  onRequest({ request }) {
    const store = isAdminPath(request.url) ? adminSessionStore : sessionStore;
    const session = store.get();
    if (session) {
      request.headers.set('Authorization', `Bearer ${session.accessToken}`);
    }
    return request;
  },
  onResponse({ request, response }) {
    if (response.status === 401) {
      const store = isAdminPath(request.url) ? adminSessionStore : sessionStore;
      store.clear();
    }
    return response;
  },
};

export const fetchClient = createFetchClient<paths>({ baseUrl });
fetchClient.use(authMiddleware);

export const $api = createQueryClient(fetchClient);

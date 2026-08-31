import createFetchClient, { type Middleware } from 'openapi-fetch';
import createQueryClient from 'openapi-react-query';
import { sessionStore } from '../auth/sessionStore';
import type { paths } from './generated/schema';

export const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * Signup (POST /subscriptions) is the only public write; every other write/read past it
 * needs the bearer token. There's no login endpoint to fall back to (ADR-0003) and no
 * refresh, so a 401 here means the session is over for good — see sessionStore.ts.
 */
const authMiddleware: Middleware = {
  onRequest({ request }) {
    const session = sessionStore.get();
    if (session) {
      request.headers.set('Authorization', `Bearer ${session.accessToken}`);
    }
    return request;
  },
  onResponse({ response }) {
    if (response.status === 401) {
      sessionStore.clear();
    }
    return response;
  },
};

export const fetchClient = createFetchClient<paths>({ baseUrl });
fetchClient.use(authMiddleware);

export const $api = createQueryClient(fetchClient);

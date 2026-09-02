import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

/**
 * Admin sign-in via Keycloak (ADR-0009): Authorization Code + PKCE, run entirely by
 * this SPA against Keycloak directly — no backend involvement until the resulting
 * access token is presented as a bearer token on `/api/v1/admin/**`. `oidc-client-ts`
 * owns the PKCE code-verifier/state handshake and the authorization-code-for-token
 * exchange; this app only ever sees the finished access token (see
 * `AdminSsoCallbackPage`), which then flows into the same `adminSessionStore` every
 * other admin page already reads from.
 *
 * <p>`userStore` is `sessionStorage`, not `localStorage` — it only needs to survive the
 * redirect round-trip to Keycloak and back, not the admin's whole session; long-lived
 * persistence is `adminSessionStore`'s job (localStorage), unchanged by this flow.
 */
export const adminUserManager = new UserManager({
  authority: import.meta.env.VITE_ADMIN_SSO_ISSUER_URI ?? 'http://localhost:8180/realms/subscription-billing',
  client_id: import.meta.env.VITE_ADMIN_SSO_CLIENT_ID ?? 'admin-console',
  redirect_uri: `${window.location.origin}/admin/sso-callback`,
  response_type: 'code',
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

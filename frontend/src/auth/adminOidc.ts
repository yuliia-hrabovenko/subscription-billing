import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

/**
 * Admin sign-in via Auth0: Authorization Code + PKCE, run entirely by
 * this SPA against Auth0 directly — no backend involvement until the resulting
 * access token is presented as a bearer token on `/api/v1/admin/**`. `oidc-client-ts`
 * owns the PKCE code-verifier/state handshake and the authorization-code-for-token
 * exchange; this app only ever sees the finished access token
 * which then flows into the same `adminSessionStore` every
 * other admin page already reads from.
 */
export const adminUserManager = new UserManager({
  authority: import.meta.env.VITE_ADMIN_SSO_ISSUER_URI ?? 'https://changeme.auth0.com',
  client_id: import.meta.env.VITE_ADMIN_SSO_CLIENT_ID ?? 'changeme-auth0-client-id',
  redirect_uri: `${window.location.origin}/admin/sso-callback`,
  response_type: 'code',
  scope: 'openid profile',
  // Auth0-specific: without an `audience` naming a registered API, Auth0's /authorize
  // returns an opaque access token (userinfo-only, not a JWT) instead of a signed JWT —
  // adminSessionStore.decodeClaims expects a real JWT, so this is required, not optional.
  extraQueryParams: import.meta.env.VITE_ADMIN_SSO_AUDIENCE
    ? { audience: import.meta.env.VITE_ADMIN_SSO_AUDIENCE }
    : {},
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

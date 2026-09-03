# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

Structure:
- api/ — typed API layer. generated/schema.d.ts is generated from openapi/api-docs.json via npm run generate:api (OpenAPI is the source of truth, per project rules). client.ts wires an openapi-fetch client with an authMiddleware that attaches bearer tokens and clears sessions on 401 — it picks between two separate token stores based on whether the request path is under /api/v1/admin.
- auth/ — two fully separate auth stacks, customer (AuthContext/sessionStore, password login) and admin (AdminAuthContext/adminSessionStore, Auth0 SSO — see adminOidc.ts, docs/operations/auth0-admin-sso-setup.md), each backed by its own localStorage-based session store (plain class, not React state, so the API middleware can read it outside the component tree). No refresh endpoint exists (per ADR-0003) — token expiry just signs the user out.
- routes/ — AppRoutes.tsx is the customer-facing route tree (plans, signup, dashboard, invoices) wrapped in AppLayout; admin/* delegates to AdminRoutes.tsx, a separate tree (login, sso-callback, overview, customers, subscriptions, plans, invoices) wrapped in AdminLayout and gated by RequireAdminAuth. RequireAuth/RequireAdminAuth are route guards per persona.
- features/ — feature-sliced, one folder per capability: subscription (signup, dashboard), invoices, plans (customer-facing), and admin/{auth,customers,subscriptions,invoices,overview,plans} (admin console) — admin/auth holds the SSO login button and the sso-callback page, not a credentials form. Each feature pairs a page component with its own useX data hooks.
- components/ — shared chrome/UI: AppLayout, AdminLayout (dark sidebar), plus StatCard, StatusChip, EmptyState, ErrorState, LoadingState.
- schemas/ — Zod validation schemas for forms (signup, create plan, add price version), each with a colocated test.
- theme.ts — MUI theme.

## Admin SSO (dev)

Admin sign-in redirects to Auth0, which is SaaS-only — there's no bundled local
container.
Once that's done, set here:

- `VITE_ADMIN_SSO_ISSUER_URI` — your Auth0 tenant, e.g. `https://{yourTenant}.auth0.com`
- `VITE_ADMIN_SSO_CLIENT_ID` — the registered application's client id
- `VITE_ADMIN_SSO_AUDIENCE` — your custom API's identifier (required — without it Auth0
  returns an opaque token, not a JWT)
- `VITE_ADMIN_SSO_CLAIM_NAMESPACE` — the namespace your Post-Login Action uses for the
  `groups`/`preferred_username` claims (usually the same value as the audience)

All four are required; `adminOidc.ts`'s fallback values are placeholders, not a working
default.

## Card collection (dev)

Signup for a paid Plan collects the card directly via Stripe Elements
(`SignupPage.tsx`/`stripeClient.ts`), never through this app's own backend — see. Set:

- `VITE_STRIPE_PUBLISHABLE_KEY` — a Stripe test-mode publishable key, from the same
  Stripe test account the backend's `STRIPE_API_KEY` points at (they must match, or
  SetupIntent confirmation fails)

`stripeClient.ts`'s fallback is a placeholder, not a working default. Without a real key
set on both frontend and backend, only free-Plan signup works locally.

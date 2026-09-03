import { loadStripe } from '@stripe/stripe-js';

/**
 * Loaded once at module scope, per Stripe's own recommendation — re-calling `loadStripe`
 * on every render would re-fetch and re-initialize Stripe.js each time. The publishable
 * key is safe to ship to the browser (it identifies the Stripe account, not a secret);
 * `VITE_STRIPE_PUBLISHABLE_KEY`'s fallback is a placeholder, not a working default —
 * override it with a real Stripe test-mode publishable key matching the backend's own
 * `STRIPE_API_KEY` (same convention as `adminOidc.ts`'s Auth0 fallbacks).
 */
export const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY ?? 'pk_test_changeme');

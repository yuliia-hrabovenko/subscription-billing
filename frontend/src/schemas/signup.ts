import { z } from 'zod';

/**
 * Mirrors SignupRequest's format constraints (email). Card data itself isn't part of
 * this schema at all — it's collected by Stripe Elements' CardElement, which manages its
 * own internal state outside react-hook-form, and only ever leaves the browser as a
 * gateway-tokenized PaymentMethod id. The trial-vs-payment cross-field
 * rule (a paid Plan needs a card) is enforced by the page itself before submitting,
 * driven by the selected Plan's price, not duplicated here.
 */
export const signupFormSchema = z.object({
  email: z.email(),
  password: z.string().min(8, 'Password must be at least 8 characters'),
  useTrial: z.boolean(),
});

export type SignupFormValues = z.infer<typeof signupFormSchema>;

import { z } from 'zod';

/**
 * Mirrors SignupRequest's format constraints (email). The trial-vs-payment cross-field
 * rule is enforced server-side (PAYMENT_METHOD_REQUIRED) rather than duplicated here,
 * since the frontend can't independently know plan eligibility rules. No `.transform()`
 * here deliberately: it would make this schema's inferred input/output types diverge,
 * which RHF's zodResolver can't reconcile with a single form-values generic — an empty
 * paymentMethodToken is normalized to `undefined` at the call site instead.
 */
export const signupFormSchema = z.object({
  email: z.email(),
  useTrial: z.boolean(),
  paymentMethodToken: z.string().trim().optional(),
});

export type SignupFormValues = z.infer<typeof signupFormSchema>;

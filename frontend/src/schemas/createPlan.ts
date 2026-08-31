import { z } from 'zod';

/**
 * initialPrice stays a string (not z.coerce.number()) so the schema's input and output
 * types match -- RHF's zodResolver needs a single form-values generic, the same reason
 * signup.ts avoids .transform(). Converted to a number at the call site instead.
 */
export const createPlanFormSchema = z.object({
  code: z.string().trim().min(1, 'Code is required'),
  name: z.string().trim().min(1, 'Name is required'),
  initialPrice: z
    .string()
    .trim()
    .min(1, 'Price is required')
    .refine((value) => Number.isFinite(Number(value)) && Number(value) > 0, 'Price must be greater than zero'),
});

export type CreatePlanFormValues = z.infer<typeof createPlanFormSchema>;

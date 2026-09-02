import { z } from 'zod';

export const addPriceVersionFormSchema = z.object({
  amount: z
    .string()
    .trim()
    .min(1, 'Amount is required')
    .refine((value) => Number.isFinite(Number(value)) && Number(value) > 0, 'Amount must be greater than zero'),
  effectiveFrom: z.string().trim().min(1, 'Effective date is required'),
});

export type AddPriceVersionFormValues = z.infer<typeof addPriceVersionFormSchema>;

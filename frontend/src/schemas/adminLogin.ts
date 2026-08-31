import { z } from 'zod';

export const adminLoginFormSchema = z.object({
  username: z.string().trim().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
});

export type AdminLoginFormValues = z.infer<typeof adminLoginFormSchema>;

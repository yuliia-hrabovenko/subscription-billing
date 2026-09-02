import { describe, expect, it } from 'vitest';
import { signupFormSchema } from './signup';

describe('signupFormSchema', () => {
  it('accepts a valid trial signup with no payment method', () => {
    const result = signupFormSchema.safeParse({ email: 'a@example.com', useTrial: true });

    expect(result.success).toBe(true);
    expect(result.data?.paymentMethodToken).toBeUndefined();
  });

  it('rejects an invalid email', () => {
    const result = signupFormSchema.safeParse({ email: 'not-an-email', useTrial: false });

    expect(result.success).toBe(false);
  });

  it('trims a provided payment method token', () => {
    const result = signupFormSchema.safeParse({ email: 'a@example.com', useTrial: false, paymentMethodToken: '  tok_visa  ' });

    expect(result.data?.paymentMethodToken).toBe('tok_visa');
  });
});

import { describe, expect, it } from 'vitest';
import { signupFormSchema } from './signup';

describe('signupFormSchema', () => {
  it('accepts a valid trial signup', () => {
    const result = signupFormSchema.safeParse({ email: 'a@example.com', password: 'password123', useTrial: true });

    expect(result.success).toBe(true);
  });

  it('rejects an invalid email', () => {
    const result = signupFormSchema.safeParse({ email: 'not-an-email', password: 'password123', useTrial: false });

    expect(result.success).toBe(false);
  });

  it('rejects a password shorter than 8 characters', () => {
    const result = signupFormSchema.safeParse({ email: 'a@example.com', password: 'short', useTrial: false });

    expect(result.success).toBe(false);
  });
});

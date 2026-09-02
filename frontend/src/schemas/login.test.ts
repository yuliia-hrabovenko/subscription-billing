import { describe, expect, it } from 'vitest';
import { loginFormSchema } from './login';

describe('loginFormSchema', () => {
  it('accepts a valid email and non-blank password', () => {
    const result = loginFormSchema.safeParse({ email: 'a@example.com', password: 'hunter2' });

    expect(result.success).toBe(true);
  });

  it('rejects an invalid email', () => {
    const result = loginFormSchema.safeParse({ email: 'not-an-email', password: 'hunter2' });

    expect(result.success).toBe(false);
  });

  it('rejects a blank password', () => {
    const result = loginFormSchema.safeParse({ email: 'a@example.com', password: '' });

    expect(result.success).toBe(false);
  });
});

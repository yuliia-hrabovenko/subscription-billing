import { describe, expect, it } from 'vitest';
import { adminLoginFormSchema } from './adminLogin';

describe('adminLoginFormSchema', () => {
  it('accepts a non-blank username and password', () => {
    const result = adminLoginFormSchema.safeParse({ username: 'admin', password: 'hunter2' });

    expect(result.success).toBe(true);
  });

  it('rejects a blank username', () => {
    const result = adminLoginFormSchema.safeParse({ username: '', password: 'hunter2' });

    expect(result.success).toBe(false);
  });

  it('rejects a blank password', () => {
    const result = adminLoginFormSchema.safeParse({ username: 'admin', password: '' });

    expect(result.success).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';
import { addPriceVersionFormSchema } from './addPriceVersion';

describe('addPriceVersionFormSchema', () => {
  it('accepts a valid price version', () => {
    const result = addPriceVersionFormSchema.safeParse({ amount: '19.99', effectiveFrom: '2026-09-01' });

    expect(result.success).toBe(true);
  });

  it('rejects a non-numeric amount', () => {
    const result = addPriceVersionFormSchema.safeParse({ amount: 'abc', effectiveFrom: '2026-09-01' });

    expect(result.success).toBe(false);
  });

  it('rejects a zero or negative amount', () => {
    expect(addPriceVersionFormSchema.safeParse({ amount: '0', effectiveFrom: '2026-09-01' }).success).toBe(false);
    expect(addPriceVersionFormSchema.safeParse({ amount: '-1', effectiveFrom: '2026-09-01' }).success).toBe(false);
  });

  it('rejects a blank effective date', () => {
    const result = addPriceVersionFormSchema.safeParse({ amount: '19.99', effectiveFrom: '' });

    expect(result.success).toBe(false);
  });
});

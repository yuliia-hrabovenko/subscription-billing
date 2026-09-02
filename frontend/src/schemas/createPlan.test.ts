import { describe, expect, it } from 'vitest';
import { createPlanFormSchema } from './createPlan';

describe('createPlanFormSchema', () => {
  it('accepts a valid plan', () => {
    const result = createPlanFormSchema.safeParse({ code: 'PRO', name: 'Pro', initialPrice: '29.99' });

    expect(result.success).toBe(true);
  });

  it('rejects a blank code', () => {
    const result = createPlanFormSchema.safeParse({ code: '', name: 'Pro', initialPrice: '29.99' });

    expect(result.success).toBe(false);
  });

  it('rejects a blank name', () => {
    const result = createPlanFormSchema.safeParse({ code: 'PRO', name: '', initialPrice: '29.99' });

    expect(result.success).toBe(false);
  });

  it('rejects a non-numeric price', () => {
    const result = createPlanFormSchema.safeParse({ code: 'PRO', name: 'Pro', initialPrice: 'abc' });

    expect(result.success).toBe(false);
  });

  it('rejects a zero or negative price', () => {
    expect(createPlanFormSchema.safeParse({ code: 'PRO', name: 'Pro', initialPrice: '0' }).success).toBe(false);
    expect(createPlanFormSchema.safeParse({ code: 'PRO', name: 'Pro', initialPrice: '-5' }).success).toBe(false);
  });
});

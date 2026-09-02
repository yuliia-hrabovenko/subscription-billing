import { describe, expect, it } from 'vitest';
import { describeError, parseApiError } from './apiError';

describe('parseApiError', () => {
  it('extracts the error envelope when the shape matches', () => {
    const body = { error: { code: 'PLAN_UNAVAILABLE_FOR_SIGNUP', message: 'Plan is not available', correlationId: 'abc-123' } };

    expect(parseApiError(body)).toEqual(body.error);
  });

  it('returns null for a body that does not match the envelope', () => {
    expect(parseApiError({ message: 'not the right shape' })).toBeNull();
    expect(parseApiError(null)).toBeNull();
    expect(parseApiError(undefined)).toBeNull();
    expect(parseApiError('a string')).toBeNull();
  });
});

describe('describeError', () => {
  it('prefers the API error envelope message', () => {
    const body = { error: { code: 'FORBIDDEN', message: 'You do not own this resource', correlationId: 'xyz' } };

    expect(describeError(body)).toBe('You do not own this resource');
  });

  it('falls back to a plain Error message', () => {
    expect(describeError(new Error('network down'))).toBe('network down');
  });

  it('falls back to a generic message for anything else', () => {
    expect(describeError('unexpected')).toBe('Something went wrong. Please try again.');
    expect(describeError(null)).toBe('Something went wrong. Please try again.');
  });
});

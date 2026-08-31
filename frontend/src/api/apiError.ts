import { z } from 'zod';

const apiErrorEnvelopeSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
    correlationId: z.string(),
  }),
});

export type ApiError = z.infer<typeof apiErrorEnvelopeSchema>['error'];

/**
 * The backend doesn't (yet) describe its error responses in the OpenAPI spec, so the
 * generated client can't type them — validate the real runtime shape ({@code
 * GlobalExceptionHandler}'s `{ error: { code, message, correlationId } }` envelope)
 * defensively instead of trusting an untyped `unknown`.
 */
export function parseApiError(body: unknown): ApiError | null {
  const result = apiErrorEnvelopeSchema.safeParse(body);
  return result.success ? result.data.error : null;
}

export function describeError(error: unknown): string {
  const apiError = parseApiError(error);
  if (apiError) {
    return apiError.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Something went wrong. Please try again.';
}

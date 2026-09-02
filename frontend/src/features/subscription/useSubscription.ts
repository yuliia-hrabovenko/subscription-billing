import { useQueryClient } from '@tanstack/react-query';
import { $api } from '../../api/client';
import { useAuth } from '../../auth/useAuth';

export function useSubscription() {
  const { session } = useAuth();
  const subscriptionId = session?.subscriptionId ?? '';

  return $api.useQuery(
    'get',
    '/api/v1/subscriptions/{id}',
    { params: { path: { id: subscriptionId } } },
    { enabled: subscriptionId !== '' },
  );
}

function useInvalidateSubscription() {
  const queryClient = useQueryClient();
  const { session } = useAuth();
  const subscriptionId = session?.subscriptionId ?? '';

  return () =>
    queryClient.invalidateQueries({
      queryKey: $api.queryOptions('get', '/api/v1/subscriptions/{id}', { params: { path: { id: subscriptionId } } }).queryKey,
    });
}

function idempotencyKey(): string {
  return crypto.randomUUID();
}

export function useCancelSubscription() {
  const invalidate = useInvalidateSubscription();
  return $api.useMutation('post', '/api/v1/subscriptions/{id}/cancel', { onSuccess: invalidate });
}

export function useUndoCancelSubscription() {
  const invalidate = useInvalidateSubscription();
  return $api.useMutation('post', '/api/v1/subscriptions/{id}/undo-cancel', { onSuccess: invalidate });
}

export function usePlanChange() {
  const invalidate = useInvalidateSubscription();
  return $api.useMutation('post', '/api/v1/subscriptions/{id}/plan-change', { onSuccess: invalidate });
}

export function useRetryPayment() {
  const invalidate = useInvalidateSubscription();
  return $api.useMutation('post', '/api/v1/subscriptions/{id}/retry-payment', { onSuccess: invalidate });
}

export { idempotencyKey };

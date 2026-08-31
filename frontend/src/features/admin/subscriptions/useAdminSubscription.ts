import { $api } from '../../../api/client';

export function useAdminSubscription(subscriptionId: string) {
  return $api.useQuery(
    'get',
    '/api/v1/admin/subscriptions/{id}',
    { params: { path: { id: subscriptionId } } },
    { enabled: subscriptionId !== '' },
  );
}

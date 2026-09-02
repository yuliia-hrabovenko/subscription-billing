import { $api } from '../../../api/client';

export function useAdminPlan(planId: string) {
  return $api.useQuery(
    'get',
    '/api/v1/admin/plans/{id}',
    { params: { path: { id: planId } } },
    { enabled: planId !== '' },
  );
}

import { $api } from '../../../api/client';

export function useAdminPlans() {
  return $api.useQuery('get', '/api/v1/admin/plans');
}

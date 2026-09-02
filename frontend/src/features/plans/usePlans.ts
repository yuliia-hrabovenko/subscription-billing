import { $api } from '../../api/client';

export function usePlans() {
  return $api.useQuery('get', '/api/v1/plans');
}

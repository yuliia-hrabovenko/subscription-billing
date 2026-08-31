import { $api } from '../../../api/client';

export function useAdminCustomer(customerId: string) {
  return $api.useQuery(
    'get',
    '/api/v1/admin/customers/{id}',
    { params: { path: { id: customerId } } },
    { enabled: customerId !== '' },
  );
}

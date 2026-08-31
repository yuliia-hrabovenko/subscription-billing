import { $api } from '../../../api/client';

export function useAdminLogin() {
  return $api.useMutation('post', '/api/v1/admin/login');
}

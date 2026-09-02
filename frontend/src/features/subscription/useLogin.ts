import { $api } from '../../api/client';

export function useLogin() {
  return $api.useMutation('post', '/api/v1/customers/login');
}

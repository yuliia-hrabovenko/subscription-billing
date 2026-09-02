import { $api } from '../../api/client';

export function useSignup() {
  return $api.useMutation('post', '/api/v1/subscriptions');
}

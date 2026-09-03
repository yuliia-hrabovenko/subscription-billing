import { $api } from '../../api/client';

export function useSetupIntent() {
  return $api.useMutation('post', '/api/v1/payment-methods/setup-intent');
}

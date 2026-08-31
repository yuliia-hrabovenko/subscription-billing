import { useQueryClient } from '@tanstack/react-query';
import { $api } from '../../../api/client';

export function useCreatePlan() {
  const queryClient = useQueryClient();
  return $api.useMutation('post', '/api/v1/admin/plans', {
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: $api.queryOptions('get', '/api/v1/admin/plans').queryKey }),
  });
}

import { useQueryClient } from '@tanstack/react-query';
import { $api } from '../../../api/client';

export function useAddPriceVersion(planId: string) {
  const queryClient = useQueryClient();
  return $api.useMutation('post', '/api/v1/admin/plans/{id}/price-versions', {
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: $api.queryOptions('get', '/api/v1/admin/plans/{id}', { params: { path: { id: planId } } }).queryKey,
      }),
  });
}

import { useInfiniteQuery } from '@tanstack/react-query';
import { $api, fetchClient } from '../../api/client';
import { useAuth } from '../../auth/useAuth';

/**
 * Raw useInfiniteQuery with a hand-written queryFn rather than openapi-react-query's
 * wrapper: that wrapper injects initialPageParam straight into the query string as the
 * first request's cursor value, but this backend has no cursor value that means "first
 * page" — it rejects anything but a real one with INVALID_CURSOR. Writing the queryFn
 * directly means the cursor param is only included once a real one exists.
 */
export function useInvoices() {
  const { session } = useAuth();
  const subscriptionId = session?.subscriptionId ?? '';

  return useInfiniteQuery({
    queryKey: ['invoices', subscriptionId],
    queryFn: async ({ pageParam }: { pageParam: string | undefined }) => {
      const { data, error } = await fetchClient.GET('/api/v1/subscriptions/{id}/invoices', {
        params: {
          path: { id: subscriptionId },
          query: pageParam ? { cursor: pageParam, limit: '20' } : { limit: '20' },
        },
      });
      if (error) {
        throw error;
      }
      return data;
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: subscriptionId !== '',
  });
}

export function useInvoice(invoiceId: string) {
  return $api.useQuery('get', '/api/v1/invoices/{id}', { params: { path: { id: invoiceId } } }, { enabled: invoiceId !== '' });
}

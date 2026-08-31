import { $api, fetchClient } from '../../api/client';
import { useCursorPaginatedQuery } from '../../api/useCursorPaginatedQuery';
import { useAuth } from '../../auth/useAuth';

export function useInvoices() {
  const { session } = useAuth();
  const subscriptionId = session?.subscriptionId ?? '';

  return useCursorPaginatedQuery({
    queryKey: ['invoices', subscriptionId],
    fetchPage: async (cursor) => {
      const { data, error } = await fetchClient.GET('/api/v1/subscriptions/{id}/invoices', {
        params: {
          path: { id: subscriptionId },
          query: cursor ? { cursor, limit: '20' } : { limit: '20' },
        },
      });
      if (error) {
        throw error;
      }
      return data;
    },
    enabled: subscriptionId !== '',
  });
}

export function useInvoice(invoiceId: string) {
  return $api.useQuery('get', '/api/v1/invoices/{id}', { params: { path: { id: invoiceId } } }, { enabled: invoiceId !== '' });
}

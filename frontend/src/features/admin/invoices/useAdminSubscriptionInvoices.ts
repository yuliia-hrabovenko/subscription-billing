import { fetchClient } from '../../../api/client';
import { useCursorPaginatedQuery } from '../../../api/useCursorPaginatedQuery';

export function useAdminSubscriptionInvoices(subscriptionId: string) {
  return useCursorPaginatedQuery({
    queryKey: ['admin', 'subscriptions', subscriptionId, 'invoices'],
    fetchPage: async (cursor) => {
      const { data, error } = await fetchClient.GET('/api/v1/admin/subscriptions/{id}/invoices', {
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

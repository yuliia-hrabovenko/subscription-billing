import { fetchClient } from '../../../api/client';
import { useCursorPaginatedQuery } from '../../../api/useCursorPaginatedQuery';

export function useAdminCustomers() {
  return useCursorPaginatedQuery({
    queryKey: ['admin', 'customers'],
    fetchPage: async (cursor) => {
      const { data, error } = await fetchClient.GET('/api/v1/admin/customers', {
        params: { query: cursor ? { cursor, limit: '20' } : { limit: '20' } },
      });
      if (error) {
        throw error;
      }
      return data;
    },
  });
}

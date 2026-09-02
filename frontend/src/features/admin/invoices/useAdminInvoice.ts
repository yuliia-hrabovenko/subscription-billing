import { $api } from '../../../api/client';

export function useAdminInvoice(invoiceId: string) {
  return $api.useQuery(
    'get',
    '/api/v1/admin/invoices/{id}',
    { params: { path: { id: invoiceId } } },
    { enabled: invoiceId !== '' },
  );
}

import { List, ListItem, ListItemText, Stack, Typography } from '@mui/material';
import { useParams } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { StatusChip } from '../../../components/StatusChip';
import { useAdminInvoice } from './useAdminInvoice';

export function AdminInvoiceDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: invoice, isLoading, isError, error, refetch } = useAdminInvoice(id);

  if (isLoading) {
    return <LoadingState label="Loading invoice…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!invoice) {
    return <EmptyState message="Invoice not found." />;
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          {invoice.planName} — {invoice.billingPeriod}
        </Typography>
        {invoice.status && <StatusChip status={invoice.status} />}
      </Stack>
      <Typography>Amount: ${invoice.amount}</Typography>
      {invoice.createdAt && (
        <Typography color="text.secondary">Created {new Date(invoice.createdAt).toLocaleString()}</Typography>
      )}

      <Typography variant="h6">Payment attempts</Typography>
      {!invoice.paymentAttempts || invoice.paymentAttempts.length === 0 ? (
        <EmptyState message="No payment attempts recorded." />
      ) : (
        <List>
          {invoice.paymentAttempts.map((attempt) => (
            <ListItem key={attempt.id}>
              <ListItemText
                primary={attempt.status}
                secondary={attempt.attemptedAt ? new Date(attempt.attemptedAt).toLocaleString() : undefined}
              />
            </ListItem>
          ))}
        </List>
      )}
    </Stack>
  );
}

import { Button, List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { StatusChip } from '../../../components/StatusChip';
import { useAdminSubscriptionInvoices } from '../invoices/useAdminSubscriptionInvoices';
import { useAdminSubscription } from './useAdminSubscription';

export function AdminSubscriptionDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: subscription, isLoading, isError, error, refetch } = useAdminSubscription(id);
  const invoices = useAdminSubscriptionInvoices(id);
  const navigate = useNavigate();

  if (isLoading) {
    return <LoadingState label="Loading subscription…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!subscription) {
    return <EmptyState message="Subscription not found." />;
  }

  const invoiceItems = invoices.data?.pages.flatMap((page) => page.items ?? []) ?? [];

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          {subscription.plan?.name}
        </Typography>
        {subscription.state && <StatusChip status={subscription.state} />}
      </Stack>
      <Typography color="text.secondary">Subscription ID: {subscription.id}</Typography>
      {subscription.pendingPlanChange && (
        <Typography color="text.secondary">Changing to {subscription.pendingPlanChange.name} at the next billing cycle</Typography>
      )}
      {subscription.trialEndsAt && (
        <Typography color="text.secondary">Trial ends {new Date(subscription.trialEndsAt).toLocaleDateString()}</Typography>
      )}

      <Typography variant="h6">Invoices</Typography>
      {invoices.isLoading ? (
        <LoadingState label="Loading invoices…" />
      ) : invoices.isError ? (
        <ErrorState error={invoices.error} onRetry={() => invoices.refetch()} />
      ) : invoiceItems.length === 0 ? (
        <EmptyState message="No invoices yet." />
      ) : (
        <List>
          {invoiceItems.map((invoice) => (
            <ListItemButton key={invoice.id} onClick={() => navigate(`/admin/invoices/${invoice.id}`)}>
              <ListItemText
                primary={`${invoice.planName} — $${invoice.amount}`}
                secondary={`${invoice.billingPeriod} · ${invoice.status}`}
              />
            </ListItemButton>
          ))}
        </List>
      )}
      {invoices.hasNextPage && (
        <Button
          variant="outlined"
          onClick={() => invoices.fetchNextPage()}
          disabled={invoices.isFetchingNextPage}
          sx={{ alignSelf: 'center' }}
        >
          {invoices.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </Button>
      )}
    </Stack>
  );
}

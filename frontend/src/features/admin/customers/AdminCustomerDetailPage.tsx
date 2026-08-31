import { List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { StatusChip } from '../../../components/StatusChip';
import { useAdminCustomer } from './useAdminCustomer';

export function AdminCustomerDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: customer, isLoading, isError, error, refetch } = useAdminCustomer(id);
  const navigate = useNavigate();

  if (isLoading) {
    return <LoadingState label="Loading customer…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!customer) {
    return <EmptyState message="Customer not found." />;
  }

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {customer.email}
      </Typography>
      {customer.createdAt && (
        <Typography color="text.secondary">Customer since {new Date(customer.createdAt).toLocaleDateString()}</Typography>
      )}

      <Typography variant="h6">Subscriptions</Typography>
      {!customer.subscriptions || customer.subscriptions.length === 0 ? (
        <EmptyState message="No subscriptions." />
      ) : (
        <List>
          {customer.subscriptions.map((subscription) => (
            <ListItemButton
              key={subscription.id}
              onClick={() => navigate(`/admin/subscriptions/${subscription.id}`)}
            >
              <ListItemText primary={subscription.plan?.name} secondary={subscription.id} />
              {subscription.state && <StatusChip status={subscription.state} />}
            </ListItemButton>
          ))}
        </List>
      )}
    </Stack>
  );
}

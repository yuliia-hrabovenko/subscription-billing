import { Alert, Link as MuiLink, List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { StatCard } from '../../../components/StatCard';
import { useAdminCustomers } from '../customers/useAdminCustomers';
import { useAdminPlans } from '../plans/useAdminPlans';

export function AdminOverviewPage() {
  const plans = useAdminPlans();
  const customers = useAdminCustomers();

  const totalPlans = plans.data?.length ?? 0;
  const activePlans = plans.data?.filter((plan) => !plan.retiredForSignup).length ?? 0;
  const recentCustomers = customers.data?.pages[0]?.items ?? [];

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        Overview
      </Typography>

      <Alert severity="info">
        This dashboard only shows what the backend currently supports: plan counts and a
        recent-customers list. MRR and customer-status breakdowns need an aggregation
        endpoint that doesn't exist yet.
      </Alert>

      {plans.isLoading ? (
        <LoadingState label="Loading plans…" />
      ) : plans.isError ? (
        <ErrorState error={plans.error} onRetry={() => plans.refetch()} />
      ) : (
        <Stack direction="row" spacing={2}>
          <StatCard label="Total plans" value={totalPlans} />
          <StatCard label="Active plans" value={activePlans} />
        </Stack>
      )}

      <Typography variant="h6">
        Recent customers (
        <MuiLink component={RouterLink} to="/admin/customers">
          view all
        </MuiLink>
        )
      </Typography>
      {customers.isLoading ? (
        <LoadingState label="Loading customers…" />
      ) : customers.isError ? (
        <ErrorState error={customers.error} onRetry={() => customers.refetch()} />
      ) : recentCustomers.length === 0 ? (
        <EmptyState message="No customers yet." />
      ) : (
        <List>
          {recentCustomers.map((customer) => (
            <ListItemButton key={customer.id} component={RouterLink} to={`/admin/customers/${customer.id}`}>
              <ListItemText
                primary={customer.email}
                secondary={customer.createdAt ? new Date(customer.createdAt).toLocaleDateString() : undefined}
              />
            </ListItemButton>
          ))}
        </List>
      )}
    </Stack>
  );
}

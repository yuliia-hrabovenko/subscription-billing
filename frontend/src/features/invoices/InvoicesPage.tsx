import { Button, List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Link as RouterLink } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { useInvoices } from './useInvoices';

export function InvoicesPage() {
  const { data, isLoading, isError, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } = useInvoices();

  if (isLoading) {
    return <LoadingState label="Loading invoices…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  const invoices = data?.pages.flatMap((page) => page.items ?? []) ?? [];

  if (invoices.length === 0) {
    return <EmptyState message="No invoices yet." />;
  }

  const chartData = [...invoices]
    .reverse()
    .map((invoice) => ({ billingPeriod: invoice.billingPeriod, amount: Number(invoice.amount) }));

  return (
    <Stack spacing={2}>
      <Typography variant="h4" component="h1">
        Invoices
      </Typography>
      {chartData.length > 1 && (
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="billingPeriod" />
            <YAxis />
            <Tooltip />
            <Line type="monotone" dataKey="amount" stroke="#1976d2" strokeWidth={2} dot={{ r: 3 }} />
          </LineChart>
        </ResponsiveContainer>
      )}
      <List>
        {invoices.map((invoice) => (
          <ListItemButton key={invoice.id} component={RouterLink} to={`/dashboard/invoices/${invoice.id}`}>
            <ListItemText
              primary={`${invoice.planName} — $${invoice.amount}`}
              secondary={`${invoice.billingPeriod} · ${invoice.status}`}
            />
          </ListItemButton>
        ))}
      </List>
      {hasNextPage && (
        <Button variant="outlined" onClick={() => fetchNextPage()} disabled={isFetchingNextPage} sx={{ alignSelf: 'center' }}>
          {isFetchingNextPage ? 'Loading…' : 'Load more'}
        </Button>
      )}
    </Stack>
  );
}

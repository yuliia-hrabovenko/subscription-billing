import {
  Button,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { useAdminCustomers } from './useAdminCustomers';

export function AdminCustomersPage() {
  const { data, isLoading, isError, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useAdminCustomers();
  const navigate = useNavigate();

  if (isLoading) {
    return <LoadingState label="Loading customers…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  const customers = data?.pages.flatMap((page) => page.items ?? []) ?? [];

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        Customers
      </Typography>

      {customers.length === 0 ? (
        <EmptyState message="No customers yet." />
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Email</TableCell>
                <TableCell>Customer ID</TableCell>
                <TableCell>Created</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {customers.map((customer) => (
                <TableRow
                  key={customer.id}
                  hover
                  onClick={() => navigate(`/admin/customers/${customer.id}`)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>{customer.email}</TableCell>
                  <TableCell>{customer.id}</TableCell>
                  <TableCell>{customer.createdAt ? new Date(customer.createdAt).toLocaleDateString() : ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {hasNextPage && (
        <Button variant="outlined" onClick={() => fetchNextPage()} disabled={isFetchingNextPage} sx={{ alignSelf: 'center' }}>
          {isFetchingNextPage ? 'Loading…' : 'Load more'}
        </Button>
      )}
    </Stack>
  );
}

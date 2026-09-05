import { Button, Card, CardActions, CardContent, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { usePlans } from './usePlans';

export function PlansPage() {
  const { data: plans, isLoading, isError, error, refetch } = usePlans();

  if (isLoading) {
    return <LoadingState label="Loading plans…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!plans || plans.length === 0) {
    return <EmptyState message="No plans are available right now." />;
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h4" component="h1">
        Choose a plan
      </Typography>
      {plans.map((plan) => (
        <Card key={plan.id} variant="outlined">
          <CardContent>
            <Typography variant="h6">{plan.name}</Typography>
            <Typography color="text.secondary">${plan.price} / month</Typography>
          </CardContent>
          <CardActions>
            <Button component={RouterLink} to={`/signup?planId=${plan.id}`} variant="contained">
              Choose the plan
            </Button>
          </CardActions>
        </Card>
      ))}
    </Stack>
  );
}

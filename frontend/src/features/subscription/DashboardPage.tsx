import {
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { useAuth } from '../../auth/useAuth';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { usePlans } from '../plans/usePlans';
import {
  idempotencyKey,
  useCancelSubscription,
  usePlanChange,
  useRetryPayment,
  useSubscription,
  useUndoCancelSubscription,
} from './useSubscription';

export function DashboardPage() {
  const { session } = useAuth();
  const subscriptionId = session?.subscriptionId ?? '';
  const { data: subscription, isLoading, isError, error, refetch } = useSubscription();
  const cancel = useCancelSubscription();
  const undoCancel = useUndoCancelSubscription();
  const retryPayment = useRetryPayment();
  const [planChangeOpen, setPlanChangeOpen] = useState(false);

  if (isLoading) {
    return <LoadingState label="Loading your subscription…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!subscription) {
    return <EmptyState message="No subscription found." />;
  }

  const isCanceled = subscription.state === 'CANCELED';
  const isPendingCancellation = subscription.state === 'PENDING_CANCELLATION';
  const isSuspended = subscription.state === 'SUSPENDED';

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        Your subscription
      </Typography>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={1}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="h6">{subscription.plan?.name}</Typography>
              <Chip label={subscription.state} size="small" />
            </Stack>
            {subscription.pendingPlanChange && (
              <Typography color="text.secondary">
                Changing to {subscription.pendingPlanChange.name} at the next billing cycle
              </Typography>
            )}
            {subscription.trialEndsAt && (
              <Typography color="text.secondary">
                Trial ends {new Date(subscription.trialEndsAt).toLocaleDateString()}
              </Typography>
            )}
          </Stack>
        </CardContent>
      </Card>

      {cancel.isError && <ErrorState error={cancel.error} />}
      {undoCancel.isError && <ErrorState error={undoCancel.error} />}
      {retryPayment.isError && <ErrorState error={retryPayment.error} />}

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
        {isSuspended && (
          <Button
            variant="contained"
            disabled={retryPayment.isPending}
            onClick={() =>
              retryPayment.mutate({
                params: { path: { id: subscriptionId }, header: { 'Idempotency-Key': idempotencyKey() } },
              })
            }
          >
            Retry payment
          </Button>
        )}

        {isPendingCancellation ? (
          <Button
            variant="outlined"
            disabled={undoCancel.isPending}
            onClick={() =>
              undoCancel.mutate({
                params: { path: { id: subscriptionId }, header: { 'Idempotency-Key': idempotencyKey() } },
              })
            }
          >
            Undo cancellation
          </Button>
        ) : (
          !isCanceled && (
            <Button
              variant="outlined"
              color="error"
              disabled={cancel.isPending}
              onClick={() =>
                cancel.mutate({
                  params: { path: { id: subscriptionId }, header: { 'Idempotency-Key': idempotencyKey() } },
                })
              }
            >
              Cancel subscription
            </Button>
          )
        )}

        {!isCanceled && (
          <Button variant="outlined" onClick={() => setPlanChangeOpen(true)}>
            Change plan
          </Button>
        )}
      </Stack>

      <PlanChangeDialog
        open={planChangeOpen}
        onClose={() => setPlanChangeOpen(false)}
        subscriptionId={subscriptionId}
        currentPlanId={subscription.plan?.id ?? ''}
      />
    </Stack>
  );
}

function PlanChangeDialog({
  open,
  onClose,
  subscriptionId,
  currentPlanId,
}: {
  open: boolean;
  onClose: () => void;
  subscriptionId: string;
  currentPlanId: string;
}) {
  const { data: plans, isLoading, isError, error } = usePlans();
  const planChange = usePlanChange();

  const choosePlan = (planId: string) => {
    planChange.mutate(
      { params: { path: { id: subscriptionId }, header: { 'Idempotency-Key': idempotencyKey() } }, body: { planId } },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth>
      <DialogTitle>Change plan</DialogTitle>
      <DialogContent>
        {isLoading && <LoadingState label="Loading plans…" />}
        {isError && <ErrorState error={error} />}
        {planChange.isError && <ErrorState error={planChange.error} />}
        {plans && (
          <List>
            {plans
              .filter((plan) => plan.id !== currentPlanId)
              .map((plan) => (
                <ListItemButton key={plan.id} disabled={planChange.isPending} onClick={() => choosePlan(plan.id ?? '')}>
                  <ListItemText primary={plan.name} secondary={`$${plan.price} / month`} />
                </ListItemButton>
              ))}
          </List>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

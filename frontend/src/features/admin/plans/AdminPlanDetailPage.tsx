import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useParams } from 'react-router-dom';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { StatusChip } from '../../../components/StatusChip';
import { addPriceVersionFormSchema, type AddPriceVersionFormValues } from '../../../schemas/addPriceVersion';
import { useAddPriceVersion } from './useAddPriceVersion';
import { useAdminPlan } from './useAdminPlan';
import { useRetirePlan } from './useRetirePlan';

export function AdminPlanDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: plan, isLoading, isError, error, refetch } = useAdminPlan(id);
  const retirePlan = useRetirePlan(id);
  const [addPriceOpen, setAddPriceOpen] = useState(false);

  if (isLoading) {
    return <LoadingState label="Loading plan…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!plan) {
    return <EmptyState message="Plan not found." />;
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          {plan.name} ({plan.code})
        </Typography>
        <StatusChip status={plan.retiredForSignup ? 'RETIRED' : 'ACTIVE'} />
      </Stack>

      {retirePlan.isError && <ErrorState error={retirePlan.error} />}

      <Stack direction="row" spacing={2}>
        <Button variant="outlined" onClick={() => setAddPriceOpen(true)}>
          Add price version
        </Button>
        {!plan.retiredForSignup && (
          <Button
            variant="outlined"
            color="error"
            disabled={retirePlan.isPending}
            onClick={() => plan.id && retirePlan.mutate({ params: { path: { id: plan.id } } })}
          >
            Retire plan
          </Button>
        )}
      </Stack>

      <Typography variant="h6">Price history</Typography>
      {!plan.priceHistory || plan.priceHistory.length === 0 ? (
        <EmptyState message="No price history yet." />
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Amount</TableCell>
                <TableCell>Effective from</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {plan.priceHistory.map((version) => (
                <TableRow key={version.id}>
                  <TableCell>${version.amount}</TableCell>
                  <TableCell>{version.effectiveFrom ? new Date(version.effectiveFrom).toLocaleDateString() : ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <AddPriceVersionDialog open={addPriceOpen} onClose={() => setAddPriceOpen(false)} planId={id} />
    </Stack>
  );
}

function AddPriceVersionDialog({ open, onClose, planId }: { open: boolean; onClose: () => void; planId: string }) {
  const addPriceVersion = useAddPriceVersion(planId);
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AddPriceVersionFormValues>({
    resolver: zodResolver(addPriceVersionFormSchema),
    defaultValues: { amount: '', effectiveFrom: '' },
  });

  const close = () => {
    reset();
    onClose();
  };

  const onSubmit = (values: AddPriceVersionFormValues) => {
    addPriceVersion.mutate(
      {
        params: { path: { id: planId } },
        body: { amount: Number(values.amount), effectiveFrom: new Date(values.effectiveFrom).toISOString() },
      },
      { onSuccess: close },
    );
  };

  return (
    <Dialog open={open} onClose={close} fullWidth>
      <DialogTitle>Add price version</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Controller
            name="amount"
            control={control}
            render={({ field }) => (
              <TextField {...field} label="Amount" error={!!errors.amount} helperText={errors.amount?.message} required />
            )}
          />
          <Controller
            name="effectiveFrom"
            control={control}
            render={({ field }) => (
              <TextField
                {...field}
                label="Effective from"
                type="date"
                slotProps={{ inputLabel: { shrink: true } }}
                error={!!errors.effectiveFrom}
                helperText={errors.effectiveFrom?.message}
                required
              />
            )}
          />
          {addPriceVersion.isError && <ErrorState error={addPriceVersion.error} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Cancel</Button>
        <Button variant="contained" onClick={handleSubmit(onSubmit)} disabled={addPriceVersion.isPending}>
          {addPriceVersion.isPending ? 'Adding…' : 'Add'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

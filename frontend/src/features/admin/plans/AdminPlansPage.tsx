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
import { useNavigate } from 'react-router-dom';
import { ErrorState } from '../../../components/ErrorState';
import { EmptyState } from '../../../components/EmptyState';
import { LoadingState } from '../../../components/LoadingState';
import { StatusChip } from '../../../components/StatusChip';
import { createPlanFormSchema, type CreatePlanFormValues } from '../../../schemas/createPlan';
import { useAdminPlans } from './useAdminPlans';
import { useCreatePlan } from './useCreatePlan';

export function AdminPlansPage() {
  const { data: plans, isLoading, isError, error, refetch } = useAdminPlans();
  const [createOpen, setCreateOpen] = useState(false);
  const navigate = useNavigate();

  if (isLoading) {
    return <LoadingState label="Loading plans…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h4" component="h1">
          Plans
        </Typography>
        <Button variant="contained" onClick={() => setCreateOpen(true)}>
          + New Plan
        </Button>
      </Stack>

      {!plans || plans.length === 0 ? (
        <EmptyState message="No plans yet." />
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Current price</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {plans.map((plan) => (
                <TableRow key={plan.id} hover onClick={() => navigate(`/admin/plans/${plan.id}`)} sx={{ cursor: 'pointer' }}>
                  <TableCell>{plan.code}</TableCell>
                  <TableCell>{plan.name}</TableCell>
                  <TableCell>${plan.currentPrice}</TableCell>
                  <TableCell>
                    <StatusChip status={plan.retiredForSignup ? 'RETIRED' : 'ACTIVE'} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreatePlanDialog open={createOpen} onClose={() => setCreateOpen(false)} />
    </Stack>
  );
}

function CreatePlanDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const createPlan = useCreatePlan();
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreatePlanFormValues>({
    resolver: zodResolver(createPlanFormSchema),
    defaultValues: { code: '', name: '', initialPrice: '' },
  });

  const close = () => {
    reset();
    onClose();
  };

  const onSubmit = (values: CreatePlanFormValues) => {
    createPlan.mutate(
      { body: { code: values.code, name: values.name, initialPrice: Number(values.initialPrice) } },
      { onSuccess: close },
    );
  };

  return (
    <Dialog open={open} onClose={close} fullWidth>
      <DialogTitle>New plan</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Controller
            name="code"
            control={control}
            render={({ field }) => (
              <TextField {...field} label="Code" error={!!errors.code} helperText={errors.code?.message} required />
            )}
          />
          <Controller
            name="name"
            control={control}
            render={({ field }) => (
              <TextField {...field} label="Name" error={!!errors.name} helperText={errors.name?.message} required />
            )}
          />
          <Controller
            name="initialPrice"
            control={control}
            render={({ field }) => (
              <TextField
                {...field}
                label="Initial price"
                error={!!errors.initialPrice}
                helperText={errors.initialPrice?.message}
                required
              />
            )}
          />
          {createPlan.isError && <ErrorState error={createPlan.error} />}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>Cancel</Button>
        <Button variant="contained" onClick={handleSubmit(onSubmit)} disabled={createPlan.isPending}>
          {createPlan.isPending ? 'Creating…' : 'Create'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

import { Chip, type ChipProps } from '@mui/material';

const COLOR_BY_STATUS: Record<string, ChipProps['color']> = {
  ACTIVE: 'success',
  TRIALING: 'info',
  PENDING_CANCELLATION: 'warning',
  SUSPENDED: 'error',
  CANCELED: 'default',
  PAID: 'success',
  PENDING: 'warning',
  FAILED: 'error',
  RETIRED: 'default',
};

export function StatusChip({ status }: { status: string }) {
  return <Chip label={status} color={COLOR_BY_STATUS[status] ?? 'default'} size="small" />;
}

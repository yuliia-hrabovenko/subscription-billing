import { Box, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, py: 6 }}>
      <Typography color="text.secondary">{message}</Typography>
      {action}
    </Box>
  );
}

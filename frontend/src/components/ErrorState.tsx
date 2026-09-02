import { Alert, Button, Stack } from '@mui/material';
import { describeError } from '../api/apiError';

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Stack spacing={2} sx={{ py: 4 }} role="alert">
      <Alert severity="error">{describeError(error)}</Alert>
      {onRetry && (
        <Button variant="outlined" onClick={onRetry} sx={{ alignSelf: 'flex-start' }}>
          Try again
        </Button>
      )}
    </Stack>
  );
}

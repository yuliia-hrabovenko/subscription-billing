import { Button, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

export function SessionExpiredPage() {
  return (
    <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
      <Typography variant="h4" component="h1">
        Session expired
      </Typography>
      <Typography color="text.secondary">
        Your session has ended. There's no way to sign back in to an existing subscription yet — start a new signup to
        continue.
      </Typography>
      <Button component={RouterLink} to="/" variant="contained">
        Browse plans
      </Button>
    </Stack>
  );
}

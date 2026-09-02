import { Box, Button, Paper, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import { adminUserManager } from '../../../auth/adminOidc';
import { ErrorState } from '../../../components/ErrorState';

export function AdminLoginPage() {
  const [error, setError] = useState<unknown>(null);
  const [isRedirecting, setIsRedirecting] = useState(false);

  const onSignIn = () => {
    setError(null);
    setIsRedirecting(true);
    adminUserManager.signinRedirect().catch((redirectError: unknown) => {
      setIsRedirecting(false);
      setError(redirectError);
    });
  };

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', alignItems: 'center', justifyContent: 'center', bgcolor: '#0f172a' }}>
      <Paper sx={{ p: 4, width: 360 }}>
        <Stack spacing={3}>
          <Typography variant="h5" component="h1">
            Admin sign in
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Admin access is managed through your organization's identity provider.
          </Typography>

          {error !== null && <ErrorState error={error} />}

          <Button variant="contained" onClick={onSignIn} disabled={isRedirecting}>
            {isRedirecting ? 'Redirecting…' : 'Sign in with SSO'}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

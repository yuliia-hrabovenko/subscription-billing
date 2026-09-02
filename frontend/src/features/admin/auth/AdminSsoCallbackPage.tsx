import { Box, Link as MuiLink, Stack, Typography } from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { adminUserManager } from '../../../auth/adminOidc';
import { useAdminAuth } from '../../../auth/useAdminAuth';
import { ErrorState } from '../../../components/ErrorState';

/**
 * Lands here after Keycloak redirects back with an authorization code (ADR-0009).
 * `signinRedirectCallback()` does the PKCE code-verifier check and the
 * code-for-token exchange, then this page hands the resulting access token to the
 * *existing* `useAdminAuth().signIn` — the same call `AdminLoginPage`'s old
 * password-login `onSuccess` used to make — so nothing downstream of `adminSessionStore`
 * needs to know how the token was obtained.
 *
 * <p>`handled` guards against React StrictMode's dev-time double-invoke: the
 * authorization code is single-use, so calling `signinRedirectCallback()` twice would
 * fail the second time.
 */
export function AdminSsoCallbackPage() {
  const navigate = useNavigate();
  const { signIn } = useAdminAuth();
  const [error, setError] = useState<unknown>(null);
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) {
      return;
    }
    handled.current = true;

    adminUserManager
      .signinRedirectCallback()
      .then((user) => {
        signIn(user.access_token);
        navigate('/admin', { replace: true });
      })
      .catch((callbackError: unknown) => {
        setError(callbackError);
      });
  }, [navigate, signIn]);

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', alignItems: 'center', justifyContent: 'center' }}>
      {error ? (
        <Stack spacing={2} sx={{ width: 360 }}>
          <ErrorState error={error} />
          <MuiLink component={Link} to="/admin/login">
            Back to sign in
          </MuiLink>
        </Stack>
      ) : (
        <Typography variant="body1">Completing sign in…</Typography>
      )}
    </Box>
  );
}

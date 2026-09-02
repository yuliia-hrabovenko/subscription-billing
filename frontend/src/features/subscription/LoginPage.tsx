import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Link as MuiLink, Stack, TextField, Typography } from '@mui/material';
import { Controller, useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { ErrorState } from '../../components/ErrorState';
import { loginFormSchema, type LoginFormValues } from '../../schemas/login';
import { useLogin } from './useLogin';

export function LoginPage() {
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const login = useLogin();

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = (values: LoginFormValues) => {
    login.mutate(
      { body: values },
      {
        onSuccess: (data) => {
          if (!data.accessToken) {
            return;
          }
          signIn(data.accessToken, data.subscriptionId ?? null);
          navigate(data.subscriptionId ? '/dashboard' : '/');
        },
      },
    );
  };

  return (
    <Stack spacing={3} component="form" onSubmit={handleSubmit(onSubmit)} sx={{ maxWidth: 480 }}>
      <Typography variant="h4" component="h1">
        Log in
      </Typography>

      <Controller
        name="email"
        control={control}
        render={({ field }) => (
          <TextField {...field} label="Email" type="email" error={!!errors.email} helperText={errors.email?.message} required />
        )}
      />

      <Controller
        name="password"
        control={control}
        render={({ field }) => (
          <TextField
            {...field}
            label="Password"
            type="password"
            error={!!errors.password}
            helperText={errors.password?.message}
            required
          />
        )}
      />

      {login.isError && <ErrorState error={login.error} />}

      <Button type="submit" variant="contained" disabled={login.isPending}>
        {login.isPending ? 'Logging in…' : 'Log in'}
      </Button>

      <Typography variant="body2">
        Need an account? <MuiLink component={RouterLink} to="/">Choose a plan</MuiLink>
      </Typography>
    </Stack>
  );
}

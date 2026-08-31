import { zodResolver } from '@hookform/resolvers/zod';
import { Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { Controller, useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { useAdminAuth } from '../../../auth/useAdminAuth';
import { ErrorState } from '../../../components/ErrorState';
import { adminLoginFormSchema, type AdminLoginFormValues } from '../../../schemas/adminLogin';
import { useAdminLogin } from './useAdminLogin';

export function AdminLoginPage() {
  const navigate = useNavigate();
  const { signIn } = useAdminAuth();
  const login = useAdminLogin();

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<AdminLoginFormValues>({
    resolver: zodResolver(adminLoginFormSchema),
    defaultValues: { username: '', password: '' },
  });

  const onSubmit = (values: AdminLoginFormValues) => {
    login.mutate(
      { body: values },
      {
        onSuccess: (data) => {
          if (!data.accessToken) {
            return;
          }
          signIn(data.accessToken);
          navigate('/admin');
        },
      },
    );
  };

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', alignItems: 'center', justifyContent: 'center', bgcolor: '#0f172a' }}>
      <Paper sx={{ p: 4, width: 360 }} component="form" onSubmit={handleSubmit(onSubmit)}>
        <Stack spacing={3}>
          <Typography variant="h5" component="h1">
            Admin sign in
          </Typography>

          <Controller
            name="username"
            control={control}
            render={({ field }) => (
              <TextField {...field} label="Username" error={!!errors.username} helperText={errors.username?.message} required />
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
            {login.isPending ? 'Signing in…' : 'Sign in'}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

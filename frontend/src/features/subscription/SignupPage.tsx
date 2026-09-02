import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Checkbox, FormControlLabel, Link as MuiLink, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { ErrorState } from '../../components/ErrorState';
import { signupFormSchema, type SignupFormValues } from '../../schemas/signup';
import { useSignup } from './useSignup';

export function SignupPage() {
  const [searchParams] = useSearchParams();
  const planId = searchParams.get('planId');
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const signup = useSignup();
  const [integrityError, setIntegrityError] = useState<string | null>(null);

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<SignupFormValues>({
    resolver: zodResolver(signupFormSchema),
    defaultValues: { email: '', password: '', useTrial: false, paymentMethodToken: undefined },
  });

  if (!planId) {
    return <ErrorState error={new Error('No plan was selected. Choose a plan first.')} />;
  }

  const onSubmit = (values: SignupFormValues) => {
    signup.mutate(
      {
        body: {
          planId,
          email: values.email,
          password: values.password,
          useTrial: values.useTrial,
          paymentMethodToken: values.paymentMethodToken,
        },
      },
      {
        onSuccess: (data) => {
          // The generated types mark these optional because the backend's OpenAPI spec
          // doesn't declare required response fields; SignupResponse always carries both
          // on a successful signup per the documented contract, but a malformed response
          // (e.g. a misbehaving proxy) shouldn't throw uncaught inside this callback --
          // sessionStore.set would reject an undefined token anyway, just less gracefully.
          if (!data.accessToken || !data.subscriptionId) {
            setIntegrityError('Signup succeeded but the response was incomplete. Please try again.');
            return;
          }
          signIn(data.accessToken, data.subscriptionId);
          navigate('/dashboard');
        },
      },
    );
  };

  return (
    <Stack spacing={3} component="form" onSubmit={handleSubmit(onSubmit)} sx={{ maxWidth: 480 }}>
      <Typography variant="h4" component="h1">
        Sign up
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

      <Controller
        name="useTrial"
        control={control}
        render={({ field }) => (
          <FormControlLabel
            control={<Checkbox checked={field.value} onChange={(event) => field.onChange(event.target.checked)} />}
            label="Start with a free trial"
          />
        )}
      />

      <Controller
        name="paymentMethodToken"
        control={control}
        render={({ field }) => (
          <TextField
            {...field}
            value={field.value ?? ''}
            label="Payment method token"
            helperText="Leave blank if starting a trial"
          />
        )}
      />

      {signup.isError && <ErrorState error={signup.error} />}
      {integrityError && <ErrorState error={new Error(integrityError)} />}

      <Button type="submit" variant="contained" disabled={signup.isPending}>
        {signup.isPending ? 'Signing up…' : 'Sign up'}
      </Button>

      <Typography variant="body2">
        Already have an account? <MuiLink component={RouterLink} to="/login">Log in</MuiLink>
      </Typography>
    </Stack>
  );
}

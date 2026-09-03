import { zodResolver } from '@hookform/resolvers/zod';
import { CardElement, Elements, useElements, useStripe } from '@stripe/react-stripe-js';
import { Box, Button, Checkbox, FormControlLabel, Link as MuiLink, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { usePlans } from '../plans/usePlans';
import { signupFormSchema, type SignupFormValues } from '../../schemas/signup';
import { stripePromise } from './stripeClient';
import { useSetupIntent } from './useSetupIntent';
import { useSignup } from './useSignup';

const CARD_ELEMENT_OPTIONS = {
  style: {
    base: {
      fontSize: '16px',
      color: '#1a1a1a',
      '::placeholder': { color: '#8a8a8a' },
    },
    invalid: { color: '#d32f2f' },
  },
};

/**
 * Resolves the selected Plan (for its price, to decide whether a card is required) before
 * mounting the actual form — `Elements`/`CardElement` render unconditionally once mounted,
 * so this outer component's job is deciding *whether* to render them at all, per Plan.
 */
export function SignupPage() {
  const [searchParams] = useSearchParams();
  const planId = searchParams.get('planId');
  const { data: plans, isLoading, isError, error, refetch } = usePlans();

  if (!planId) {
    return <ErrorState error={new Error('No plan was selected. Choose a plan first.')} />;
  }
  if (isLoading) {
    return <LoadingState label="Loading plan…" />;
  }
  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }
  const plan = plans?.find((candidate) => candidate.id === planId);
  if (!plan) {
    return <ErrorState error={new Error('The selected plan could not be found. Choose a plan again.')} />;
  }

  return (
    <Elements stripe={stripePromise}>
      <SignupForm planId={planId} requiresPaymentMethod={(plan.price ?? 0) > 0} />
    </Elements>
  );
}

function SignupForm({ planId, requiresPaymentMethod }: { planId: string; requiresPaymentMethod: boolean }) {
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const stripe = useStripe();
  const elements = useElements();
  const createSetupIntent = useSetupIntent();
  const signup = useSignup();
  const [integrityError, setIntegrityError] = useState<string | null>(null);
  const [cardError, setCardError] = useState<string | null>(null);
  const [isConfirmingCard, setIsConfirmingCard] = useState(false);

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<SignupFormValues>({
    resolver: zodResolver(signupFormSchema),
    defaultValues: { email: '', password: '', useTrial: false },
  });

  /**
   * A paid Plan's card never reaches this app's backend as raw data: `CardElement`
   * collects it directly into Stripe's own iframe, and `confirmCardSetup` exchanges it
   * for a PaymentMethod id against a backend-issued SetupIntent — only that id is sent
   * on to signup, as `paymentMethodToken` (see ADR-0011).
   */
  const onSubmit = async (values: SignupFormValues) => {
    setCardError(null);
    let paymentMethodToken: string | undefined;

    if (requiresPaymentMethod) {
      if (!stripe || !elements) {
        setCardError('Payment form is still loading. Please try again in a moment.');
        return;
      }
      const cardElement = elements.getElement(CardElement);
      if (!cardElement) {
        setCardError('Card details are required for this plan.');
        return;
      }

      setIsConfirmingCard(true);
      try {
        const { clientSecret } = await createSetupIntent.mutateAsync({});
        if (!clientSecret) {
          setCardError('Could not start card setup. Please try again.');
          return;
        }
        const result = await stripe.confirmCardSetup(clientSecret, {
          payment_method: { card: cardElement, billing_details: { email: values.email } },
        });
        if (result.error) {
          setCardError(result.error.message ?? 'Your card could not be verified.');
          return;
        }
        const paymentMethod = result.setupIntent?.payment_method;
        paymentMethodToken = typeof paymentMethod === 'string' ? paymentMethod : paymentMethod?.id;
        if (!paymentMethodToken) {
          setCardError('Could not confirm your card. Please try again.');
          return;
        }
      } catch {
        setCardError('Could not confirm your card. Please try again.');
        return;
      } finally {
        setIsConfirmingCard(false);
      }
    }

    signup.mutate(
      {
        body: {
          planId,
          email: values.email,
          password: values.password,
          useTrial: values.useTrial,
          paymentMethodToken,
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

  const isSubmitting = isConfirmingCard || signup.isPending;

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

      {requiresPaymentMethod && (
        <Stack spacing={1}>
          <Typography variant="body2" color="text.secondary">
            Card details
          </Typography>
          <Box
            sx={{
              border: '1px solid',
              borderColor: cardError ? 'error.main' : 'divider',
              borderRadius: 1,
              p: 1.75,
            }}
          >
            <CardElement options={CARD_ELEMENT_OPTIONS} />
          </Box>
          {cardError && (
            <Typography variant="caption" color="error">
              {cardError}
            </Typography>
          )}
        </Stack>
      )}

      {signup.isError && <ErrorState error={signup.error} />}
      {integrityError && <ErrorState error={new Error(integrityError)} />}

      <Button type="submit" variant="contained" disabled={isSubmitting}>
        {isSubmitting ? 'Signing up…' : 'Sign up'}
      </Button>

      <Typography variant="body2">
        Already have an account? <MuiLink component={RouterLink} to="/login">Log in</MuiLink>
      </Typography>
    </Stack>
  );
}

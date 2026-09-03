package com.subscriptionbilling.payments.paymentmethod;

public class SetupIntentService implements SetupIntentPort {

    private final StripePaymentMethodClient stripePaymentMethodClient;

    public SetupIntentService(StripePaymentMethodClient stripePaymentMethodClient) {
        this.stripePaymentMethodClient = stripePaymentMethodClient;
    }

    @Override
    public String createClientSecret() {
        return stripePaymentMethodClient.createSetupIntentClientSecret();
    }
}

package com.subscriptionbilling.payments.paymentmethod;

import com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort;
import com.subscriptionbilling.billingjob.paymentmethod.ProviderPaymentMethodReference;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * This module's implementation of {@link CustomerPaymentMethodPort}: resolves a
 * Customer's card-on-file reference straight from the {@link PaymentMethod} row {@link
 * PaymentMethodOnboardingService} maintains.
 */
public class PaymentMethodLookupAdapter implements CustomerPaymentMethodPort {

    private final PaymentMethodRepository paymentMethodRepository;

    public PaymentMethodLookupAdapter(PaymentMethodRepository paymentMethodRepository) {
        this.paymentMethodRepository = paymentMethodRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderPaymentMethodReference> findActiveProviderReference(UUID customerId) {
        return paymentMethodRepository.findByCustomerId(customerId)
                .map(pm -> new ProviderPaymentMethodReference(pm.getProviderCustomerId(), pm.getProviderPaymentMethodId()));
    }
}

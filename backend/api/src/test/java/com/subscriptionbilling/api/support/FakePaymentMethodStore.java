package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.paymentmethod.ProviderPaymentMethodReference;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs {@link FakePaymentMethodOnboardingPort}/{@link FakeCustomerPaymentMethodPort}:
 * an in-memory stand-in for the {@code payments} module's {@code payment_method} table,
 * shared between the two fakes so a token {@link FakePaymentMethodOnboardingPort#attach}
 * records during a real signup call is exactly what {@link
 * FakeCustomerPaymentMethodPort#findActiveProviderReference} later resolves for billing
 * -- and so a test can seed a hand-constructed Customer's card directly via {@link #put},
 * the equivalent of the old {@code Customer.setPaymentMethodToken} for tests that build a
 * due Subscription fixture without going through the real signup endpoint. {@link
 * FakePaymentMethodOnboardingPort#attach} never receives a gateway Customer id (its real
 * counterpart, {@code PaymentMethodOnboardingPort.attach}, doesn't take one either,
 * so this store synthesizes a deterministic placeholder one, the same way the
 * real adapter would report whatever Stripe Customer it created/reused during onboarding.
 */
public class FakePaymentMethodStore {

    private static final Map<UUID, ProviderPaymentMethodReference> referencesByCustomerId = new ConcurrentHashMap<>();

    public void put(UUID customerId, String providerPaymentMethodId) {
        referencesByCustomerId.put(customerId,
                new ProviderPaymentMethodReference("fake-cus-" + customerId, providerPaymentMethodId));
    }

    public Optional<ProviderPaymentMethodReference> get(UUID customerId) {
        return Optional.ofNullable(referencesByCustomerId.get(customerId));
    }
}

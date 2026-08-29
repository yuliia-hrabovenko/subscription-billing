package com.subscriptionbilling.api.webhook;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.CountingPaymentGatewayClient;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttempt;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises payment-succeeded/failed webhook reconciliation end to end against a real
 * Postgres instance: an event for an attempt not yet recorded applies the exact same
 * outcome logic {@link BillingJobRunner} uses; a sync-then-webhook race (the synchronous
 * path already recorded the outcome by the time the webhook arrives) is a provable
 * no-op; and a conflicting outcome report is rejected rather than silently applied.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOutcomeWebhookIT extends AbstractPostgresIntegrationTest {

    private static final String SIGNATURE_HEADER_NAME = WebhookController.SIGNATURE_HEADER_NAME;
    private static final String VALID_SIGNATURE = CountingPaymentGatewayClient.VALID_SIGNATURE_HEADER;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private BillingJobRunner billingJobRunner;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Test
    void aPaymentSucceededEventForAnAttemptNotYetRecordedAppliesTheSameSuccessLogicTheSyncPathUses() {
        LocalDate billingPeriod = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueSubscription(billingPeriod, "tok_visa");
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(paymentSucceededPayload(eventId, "gw-webhook-success-1", subscription.getId()))
                .assertThat().hasStatusOk();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(attempt.getGatewayReference()).isEqualTo("gw-webhook-success-1");
        });
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isAfter(billingPeriod);
    }

    @Test
    void aPaymentFailedEventForAnAttemptNotYetRecordedAppliesTheSameFailureDunningHandoffLogicTheSyncPathUses() {
        LocalDate billingPeriod = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueSubscription(billingPeriod, "tok_visa");
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(paymentFailedPayload(eventId, "gw-webhook-fail-1", subscription.getId()))
                .assertThat().hasStatusOk();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED));
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.SUSPENDED);
    }

    @Test
    void redeliveringTheSamePaymentSucceededWebhookEventIdTwiceProducesExactlyOneOutcomeApplication() {
        LocalDate billingPeriod = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueSubscription(billingPeriod, "tok_visa");
        String eventId = "evt_" + UUID.randomUUID();
        String payload = paymentSucceededPayload(eventId, "gw-webhook-redelivery-1", subscription.getId());

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();
        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(payload)
                .assertThat().hasStatusOk();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        assertThat(paymentAttemptRepository.findByInvoiceId(invoice.get().getId())).hasSize(1);
    }

    @Test
    void aPaymentSucceededWebhookForAChargeTheSyncPathAlreadyRecordedSucceededIsANoOp() {
        LocalDate billingPeriod = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueSubscription(billingPeriod, "tok_visa");

        billingJobRunner.run(); // synchronous path resolves and records the success first

        Invoice invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod).orElseThrow();
        PaymentAttempt syncAttempt = paymentAttemptRepository.findByInvoiceId(invoice.getId()).get(0);
        String gatewayReference = syncAttempt.getGatewayReference();
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(paymentSucceededPayload(eventId, gatewayReference, subscription.getId()))
                .assertThat().hasStatusOk();

        // Still exactly the one PaymentAttempt the sync path recorded -- the webhook's
        // different WebhookEventId never triggers a second application of the same
        // outcome for this gateway reference.
        assertThat(paymentAttemptRepository.findByInvoiceId(invoice.getId())).hasSize(1);
    }

    @Test
    void aPaymentFailedWebhookReportingAnOutcomeConflictingWithAnAlreadyRecordedSuccessIsRejectedNotApplied() {
        LocalDate billingPeriod = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueSubscription(billingPeriod, "tok_visa");

        billingJobRunner.run(); // synchronous path records a success for this cycle

        Invoice invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod).orElseThrow();
        PaymentAttempt syncAttempt = paymentAttemptRepository.findByInvoiceId(invoice.getId()).get(0);
        String gatewayReference = syncAttempt.getGatewayReference();
        String eventId = "evt_" + UUID.randomUUID();

        mvc.post().uri("/api/v1/webhooks/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER_NAME, VALID_SIGNATURE)
                .content(paymentFailedPayload(eventId, gatewayReference, subscription.getId()))
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("CONFLICTING_PAYMENT_OUTCOME");

        // The already-recorded success is untouched: no second (failed) PaymentAttempt.
        assertThat(paymentAttemptRepository.findByInvoiceId(invoice.getId())).hasSize(1);
        assertThat(paymentAttemptRepository.findByInvoiceId(invoice.getId()).get(0).getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    private String paymentSucceededPayload(String eventId, String gatewayReference, UUID subscriptionId) {
        return """
                {"id":"%s","type":"charge.succeeded","data":{"object":{"id":"%s","metadata":{"subscription_id":"%s"}}}}
                """.formatted(eventId, gatewayReference, subscriptionId);
    }

    private String paymentFailedPayload(String eventId, String gatewayReference, UUID subscriptionId) {
        return """
                {"id":"%s","type":"charge.failed","data":{"object":{"id":"%s","metadata":{"subscription_id":"%s"}}}}
                """.formatted(eventId, gatewayReference, subscriptionId);
    }

    private Subscription seedDueSubscription(LocalDate billingPeriod, String paymentMethodToken) {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Customer customer = new Customer(UUID.randomUUID(), "payment-outcome-it-" + UUID.randomUUID() + "@example.com");
        customer.setPaymentMethodToken(paymentMethodToken);
        customerRepository.saveAndFlush(customer);
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, proPlan, Instant.now());
        subscription.advanceDueDate(billingPeriod);
        return subscriptionRepository.saveAndFlush(subscription);
    }
}

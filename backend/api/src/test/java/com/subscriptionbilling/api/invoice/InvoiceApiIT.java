package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.api.subscription.SignupResponse;
import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.PaymentGatewayTestConfig;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subscriber's own billing history through the HTTP layer: the list endpoint pages
 * reverse-chronologically with no repeat or skip across a cursor boundary, the get-single
 * endpoint returns the full PaymentAttempt history (one on the happy path, four after
 * Dunning exhausts), a later Plan price change never alters what an already-issued
 * Invoice reports, the receipt-download endpoint serves
 * the stored PDF for a paid Invoice and a structured {@code 409} for one still open or
 * failed, and all three endpoints enforce ownership (403 for a different Customer's
 * token, 401 unauthenticated) exactly like {@code SubscriptionApiIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceApiIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private BillingJobRunner billingJobRunner;

    @Test
    void listReturnsInvoicesReverseChronologicalAndASecondPageViaTheCursorNeitherRepeatsNorSkips() {
        SignupResponse owner = immediatePaidSignUp("list-owner-" + UUID.randomUUID() + "@example.com");
        chargeForPeriod(owner.subscriptionId(), LocalDate.of(2026, 1, 31));
        chargeForPeriod(owner.subscriptionId(), LocalDate.of(2026, 2, 28));
        chargeForPeriod(owner.subscriptionId(), LocalDate.of(2026, 3, 31));

        var firstPage = mvc.get().uri("/api/v1/subscriptions/{id}/invoices?limit=2", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        firstPage.extractingPath("$.items.length()").isEqualTo(2);
        firstPage.extractingPath("$.nextCursor").isNotNull();
        InvoiceListResponse firstPageBody = readBody(mvc.get().uri("/api/v1/subscriptions/{id}/invoices?limit=2", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange(), InvoiceListResponse.class);
        // Newest first: the last billing period charged comes back first.
        assertThat(firstPageBody.items()).extracting(InvoiceSummaryResponse::billingPeriod)
                .containsExactly(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 2, 28));

        InvoiceListResponse secondPageBody = readBody(mvc.get()
                .uri("/api/v1/subscriptions/{id}/invoices?limit=2&cursor={cursor}", owner.subscriptionId(), firstPageBody.nextCursor())
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange(), InvoiceListResponse.class);
        assertThat(secondPageBody.items()).extracting(InvoiceSummaryResponse::billingPeriod)
                .containsExactly(LocalDate.of(2026, 1, 31));
        assertThat(secondPageBody.nextCursor()).isNull();

        List<UUID> allIds = new ArrayList<>();
        firstPageBody.items().forEach(item -> allIds.add(item.id()));
        secondPageBody.items().forEach(item -> allIds.add(item.id()));
        assertThat(allIds).doesNotHaveDuplicates().hasSize(3);
    }

    @Test
    void getSingleOnAHappyPathChargeReturnsExactlyOneSucceededPaymentAttempt() {
        SignupResponse owner = immediatePaidSignUp("happy-path-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 4, 30);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();

        var body = mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.status").asString().isEqualTo("PAID");
        body.extractingPath("$.paymentAttempts.length()").isEqualTo(1);
        body.extractingPath("$.paymentAttempts[0].status").asString().isEqualTo("SUCCEEDED");
    }

    @Test
    void getSingleAfterDunningExhaustionReturnsAllFourPaymentAttempts() {
        SignupResponse owner = suspendViaRealDunningFlow("dunning-history-" + UUID.randomUUID() + "@example.com");
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-1 retry fails -> retriesUsed=1
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-3 retry fails -> retriesUsed=2
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-7 retry fails -> retriesUsed=3, exhausted -> canceled
        UUID invoiceId = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow().getId();

        var body = mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.status").asString().isEqualTo("FAILED");
        body.extractingPath("$.paymentAttempts.length()").isEqualTo(4);
    }

    @Test
    void anInvoicesAmountIsUnchangedAfterAPlanPriceChangeThatOccursAfterItWasIssued() {
        SignupResponse owner = immediatePaidSignUp("price-immunity-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 5, 31);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();
        BigDecimal originalAmount = readBody(mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange(), InvoiceResponse.class).amount();

        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), proPlan, originalAmount.add(new BigDecimal("50.00")),
                        Instant.now().plusSeconds(1)));

        BigDecimal amountAfterPriceChange = readBody(mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange(), InvoiceResponse.class).amount();
        assertThat(amountAfterPriceChange).isEqualByComparingTo(originalAmount);

        InvoiceListResponse listBody = readBody(mvc.get().uri("/api/v1/subscriptions/{id}/invoices", owner.subscriptionId())
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange(), InvoiceListResponse.class);
        assertThat(listBody.items()).singleElement()
                .extracting(InvoiceSummaryResponse::amount).satisfies(amount ->
                        assertThat(amount).isEqualByComparingTo(originalAmount));
    }

    @Test
    void downloadingTheReceiptForAPaidInvoiceReturnsThePdfWithTheCorrectContentType() {
        SignupResponse owner = immediatePaidSignUp("receipt-happy-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 1, 10);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();

        MvcTestResult result = mvc.get().uri("/api/v1/invoices/{id}/receipt", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        byte[] pdf = result.getMvcResult().getResponse().getContentAsByteArray();
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void downloadingTheReceiptForAStillPendingInvoiceIsRejectedWithAStructuredErrorNotABrokenFile() {
        SignupResponse owner = suspendViaRealDunningFlow("receipt-pending-" + UUID.randomUUID() + "@example.com");
        UUID invoiceId = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow().getId();

        mvc.get().uri("/api/v1/invoices/{id}/receipt", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("RECEIPT_NOT_AVAILABLE");
    }

    @Test
    void downloadingTheReceiptForAFailedInvoiceIsRejectedWithAStructuredErrorNotABrokenFile() {
        SignupResponse owner = suspendViaRealDunningFlow("receipt-failed-" + UUID.randomUUID() + "@example.com");
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-1 retry fails -> retriesUsed=1
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-3 retry fails -> retriesUsed=2
        forceDueToday(owner.subscriptionId());
        billingJobRunner.run(); // day-7 retry fails -> retriesUsed=3, exhausted -> failed
        UUID invoiceId = invoiceRepository
                .findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), LocalDate.now(ZoneOffset.UTC))
                .orElseThrow().getId();

        mvc.get().uri("/api/v1/invoices/{id}/receipt", invoiceId)
                .header("Authorization", "Bearer " + owner.accessToken())
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("RECEIPT_NOT_AVAILABLE");
    }

    @Test
    void downloadingTheReceiptForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = immediatePaidSignUp("receipt-victim-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 1, 15);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();
        SignupResponse attacker = signUp("receipt-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/invoices/{id}/receipt", invoiceId)
                .header("Authorization", "Bearer " + attacker.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void downloadingTheReceiptWithNoTokenIsRejectedWith401() {
        SignupResponse owner = immediatePaidSignUp("receipt-noauth-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 1, 20);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();

        mvc.get().uri("/api/v1/invoices/{id}/receipt", invoiceId)
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void listForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = immediatePaidSignUp("list-victim-" + UUID.randomUUID() + "@example.com");
        SignupResponse attacker = signUp("list-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/subscriptions/{id}/invoices", owner.subscriptionId())
                .header("Authorization", "Bearer " + attacker.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void getSingleForAWrongOwnerIsRejectedWith403() {
        SignupResponse owner = immediatePaidSignUp("get-victim-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 6, 30);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();
        SignupResponse attacker = signUp("get-attacker-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + attacker.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    @Test
    void listWithNoTokenIsRejectedWith401() {
        SignupResponse owner = immediatePaidSignUp("list-noauth-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/subscriptions/{id}/invoices", owner.subscriptionId())
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void getSingleWithNoTokenIsRejectedWith401() {
        SignupResponse owner = immediatePaidSignUp("get-noauth-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();

        mvc.get().uri("/api/v1/invoices/{id}", invoiceId)
                .assertThat()
                .hasStatus(401)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("UNAUTHORIZED");
    }

    @Test
    void getSingleForANonexistentInvoiceIsRejectedWith403NotFoundOrSuccess() {
        SignupResponse someone = signUp("get-nonexistent-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/invoices/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + someone.accessToken())
                .assertThat()
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("FORBIDDEN");
    }

    /**
     * Sets the Subscription's due date directly to {@code billingPeriod} (mirrors {@code
     * SubscriptionApiIT}/{@code BillingJobRunnerIT}'s fixtures — no HTTP path schedules a
     * due date yet) and runs the real billing job, so each call opens and pays a distinct
     * Billing Cycle for the same Subscription without waiting on the calendar.
     */
    private void chargeForPeriod(UUID subscriptionId, LocalDate billingPeriod) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        subscription.advanceDueDate(billingPeriod);
        subscriptionRepository.saveAndFlush(subscription);
        billingJobRunner.run();
    }

    private SignupResponse suspendViaRealDunningFlow(String email) {
        SignupResponse signup = immediatePaidSignUp(email, PaymentGatewayTestConfig.DECLINE_TOKEN);
        Subscription subscription = subscriptionRepository.findById(signup.subscriptionId()).orElseThrow();
        subscription.advanceDueDate(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);

        billingJobRunner.run();
        return signup;
    }

    private void forceDueToday(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        subscription.scheduleRetry(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);
    }

    private SignupResponse signUp(String email) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"planId":"%s","email":"%s","password":"password123!"}
                        """.formatted(freePlanId(), email))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    private SignupResponse immediatePaidSignUp(String email) {
        return immediatePaidSignUp(email, "gw_tok_abc123");
    }

    private SignupResponse immediatePaidSignUp(String email, String paymentMethodToken) {
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"planId":"%s","email":"%s","useTrial":false,"paymentMethodToken":"%s","password":"password123!"}
                        """.formatted(proPlanId(), email, paymentMethodToken))
                .exchange();
        return readBody(result, SignupResponse.class);
    }

    private UUID freePlanId() {
        return planRepository.findByCode("free").orElseThrow().getId();
    }

    private UUID proPlanId() {
        return planRepository.findByCode("pro").orElseThrow().getId();
    }

    private <T> T readBody(MvcTestResult result, Class<T> type) {
        try {
            return jsonMapper.readValue(result.getMvcResult().getResponse().getContentAsString(), type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.subscriptionbilling.api.admin;

import com.subscriptionbilling.api.customer.CustomerDetailResponse;
import com.subscriptionbilling.api.customer.CustomerListResponse;
import com.subscriptionbilling.api.invoice.InvoiceListResponse;
import com.subscriptionbilling.api.invoice.InvoiceResponse;
import com.subscriptionbilling.api.plan.AdminPlanDetailResponse;
import com.subscriptionbilling.api.plan.AdminPlanSummaryResponse;
import com.subscriptionbilling.api.subscription.AdminSubscriptionResponse;
import com.subscriptionbilling.api.subscription.SignupResponse;
import com.subscriptionbilling.api.support.AbstractAdminIntegrationTest;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApiIT extends AbstractAdminIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private BillingJobRunner billingJobRunner;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void aRealAdminTokenReachesAnAdminEndpoint() {
        String token = adminToken();

        mvc.get().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + token)
                .assertThat()
                .hasStatusOk();
    }

    @Test
    void aTokenWithoutTheAdminGroupIsRejectedWith403() {
        mvc.get().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + nonAdminToken())
                .assertThat()
                .hasStatus(403);
    }

    @Test
    void anAdminTokenIsRejectedOnACustomerEndpoint() {
        String adminToken = adminToken();
        SignupResponse someone = signUp("admin-on-customer-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/subscriptions/{id}", someone.subscriptionId())
                .header("Authorization", "Bearer " + adminToken)
                .assertThat()
                .hasStatus(403);
    }

    @Test
    void aCustomerTokenIsRejectedOnAnAdminEndpoint() {
        SignupResponse someone = signUp("customer-on-admin-" + UUID.randomUUID() + "@example.com");

        mvc.get().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + someone.accessToken())
                .assertThat()
                .hasStatus(403);
    }

    @Test
    void anAdminEndpointWithNoTokenIsRejectedWith401() {
        mvc.get().uri("/api/v1/admin/plans")
                .assertThat()
                .hasStatus(401);
    }

    @Test
    void adminCanListAndFetchCustomersAcrossDifferentCustomersWithNoOwnershipFiltering() {
        String adminToken = adminToken();
        SignupResponse first = signUp("admin-visibility-a-" + UUID.randomUUID() + "@example.com");
        SignupResponse second = signUp("admin-visibility-b-" + UUID.randomUUID() + "@example.com");
        Subscription firstSubscription = subscriptionRepository.findById(first.subscriptionId()).orElseThrow();
        UUID firstCustomerId = firstSubscription.getCustomer().getId();

        CustomerListResponse list = readBody(mvc.get().uri("/api/v1/admin/customers?limit=100")
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), CustomerListResponse.class);
        assertThat(list.items()).extracting(item -> item.id()).contains(firstCustomerId);

        CustomerDetailResponse detail = readBody(mvc.get().uri("/api/v1/admin/customers/{id}", firstCustomerId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), CustomerDetailResponse.class);
        assertThat(detail.subscriptions()).extracting(AdminSubscriptionResponse::id).containsExactly(first.subscriptionId());
        assertThat(second).isNotNull();
    }

    @Test
    void adminCanFetchAnySubscriptionByIdRegardlessOfOwner() {
        String adminToken = adminToken();
        SignupResponse owner = signUp("admin-sub-visibility-" + UUID.randomUUID() + "@example.com");

        AdminSubscriptionResponse response = readBody(mvc.get().uri("/api/v1/admin/subscriptions/{id}", owner.subscriptionId())
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), AdminSubscriptionResponse.class);

        assertThat(response.id()).isEqualTo(owner.subscriptionId());
    }

    @Test
    void adminCanListAndFetchInvoicesForAnySubscriptionRegardlessOfOwner() {
        String adminToken = adminToken();
        SignupResponse owner = immediatePaidSignUp("admin-invoice-visibility-" + UUID.randomUUID() + "@example.com");
        LocalDate billingPeriod = LocalDate.of(2026, 3, 31);
        chargeForPeriod(owner.subscriptionId(), billingPeriod);
        UUID invoiceId = invoiceRepository.findBySubscriptionIdAndBillingPeriod(owner.subscriptionId(), billingPeriod)
                .orElseThrow().getId();

        InvoiceListResponse list = readBody(mvc.get().uri("/api/v1/admin/subscriptions/{id}/invoices", owner.subscriptionId())
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), InvoiceListResponse.class);
        assertThat(list.items()).hasSize(1);

        InvoiceResponse detail = readBody(mvc.get().uri("/api/v1/admin/invoices/{id}", invoiceId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), InvoiceResponse.class);
        assertThat(detail.id()).isEqualTo(invoiceId);
    }

    @Test
    void adminCanCreateAPlanWhichImmediatelyAppearsInTheAdminCatalog() {
        String adminToken = adminToken();
        String code = "enterprise-" + UUID.randomUUID();

        AdminPlanDetailResponse created = readBody(mvc.post().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","name":"Enterprise","initialPrice":"199.00"}
                        """.formatted(code))
                .exchange(), AdminPlanDetailResponse.class);

        assertThat(created.code()).isEqualTo(code);
        assertThat(created.priceHistory()).singleElement()
                .extracting(AdminPlanDetailResponse.PriceVersionResponse::amount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("199.00"));

        List<AdminPlanSummaryResponse> catalog = List.of(readBody(mvc.get().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), AdminPlanSummaryResponse[].class));
        assertThat(catalog).extracting(AdminPlanSummaryResponse::code).contains(code);
    }

    @Test
    void creatingAPlanWithADuplicateCodeIsRejectedWith409() {
        String adminToken = adminToken();
        String code = "dup-" + UUID.randomUUID();
        String body = """
                {"code":"%s","name":"First","initialPrice":"10.00"}
                """.formatted(code);
        mvc.post().uri("/api/v1/admin/plans").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();

        mvc.post().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .assertThat()
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("PLAN_CODE_ALREADY_EXISTS");
    }

    @Test
    void retiringAPlanRemovesItFromThePublicCatalogButItStillAppearsForAdmin() {
        String adminToken = adminToken();
        String code = "retire-me-" + UUID.randomUUID();
        mvc.post().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","name":"Retire Me","initialPrice":"10.00"}
                        """.formatted(code))
                .exchange();
        UUID planId = planRepository.findByCode(code).orElseThrow().getId();

        mvc.post().uri("/api/v1/admin/plans/{id}/retire", planId)
                .header("Authorization", "Bearer " + adminToken)
                .assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.retiredForSignup").asBoolean().isTrue();

        List<?> publicCatalog = List.of(readBody(mvc.get().uri("/api/v1/plans").exchange(), Object[].class));
        assertThat(publicCatalog.toString()).doesNotContain(code);

        AdminPlanDetailResponse adminDetail = readBody(mvc.get().uri("/api/v1/admin/plans/{id}", planId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange(), AdminPlanDetailResponse.class);
        assertThat(adminDetail.retiredForSignup()).isTrue();
    }

    @Test
    void repricingWithANonMonotonicEffectiveFromIsRejectedWith400() {
        String adminToken = adminToken();
        String code = "reprice-" + UUID.randomUUID();
        mvc.post().uri("/api/v1/admin/plans")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","name":"Reprice Me","initialPrice":"10.00"}
                        """.formatted(code))
                .exchange();
        UUID planId = planRepository.findByCode(code).orElseThrow().getId();

        mvc.post().uri("/api/v1/admin/plans/{id}/price-versions", planId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"15.00","effectiveFrom":"2000-01-01T00:00:00Z"}
                        """)
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("INVALID_PRICE_VERSION");
    }

    private void chargeForPeriod(UUID subscriptionId, LocalDate billingPeriod) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        subscription.advanceDueDate(billingPeriod);
        subscriptionRepository.saveAndFlush(subscription);
        billingJobRunner.run();
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
        MvcTestResult result = mvc.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"planId":"%s","email":"%s","useTrial":false,"paymentMethodToken":"gw_tok_abc123","password":"password123!"}
                        """.formatted(proPlanId(), email))
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

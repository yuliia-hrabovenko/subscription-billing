package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.api.error.CorrelationIdFilter;
import com.subscriptionbilling.api.error.GlobalExceptionHandler;
import com.subscriptionbilling.billingcore.plan.PlanCatalogService;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Web-layer slice test: the controller's JSON mapping, and — via the imported
 * {@link GlobalExceptionHandler} and {@link CorrelationIdFilter} — the structured error
 * envelope for a request this module rejects. No auth setup needed; this endpoint is
 * public.
 */
@WebMvcTest(PlanController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class PlanControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PlanCatalogService planCatalogService;

    @Test
    void returnsTheAvailablePlansAsJson() {
        UUID freeId = UUID.randomUUID();
        when(planCatalogService.listAvailablePlans())
                .thenReturn(List.of(new PlanSummary(freeId, "free", "Free", new BigDecimal("0.00"))));

        mvc.get().uri("/api/v1/plans").assertThat()
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        [{"id":"%s","code":"free","name":"Free","price":0.00}]
                        """.formatted(freeId));
    }

    @Test
    void aMethodThisEndpointDoesNotSupportReturnsTheStructuredErrorEnvelope() {
        mvc.post().uri("/api/v1/plans").assertThat()
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson()
                .extractingPath("$.error.code").asString().isEqualTo("METHOD_NOT_ALLOWED");

        mvc.post().uri("/api/v1/plans").assertThat()
                .bodyJson()
                .extractingPath("$.error.correlationId").asString().isNotEmpty();
    }
}

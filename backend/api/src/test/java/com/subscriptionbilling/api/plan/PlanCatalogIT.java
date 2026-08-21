package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
class PlanCatalogIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PlanRepository planRepository;

    @Test
    void listsTheSeededCatalogWithNoAuthRequired() {
        mvc.get().uri("/api/v1/plans").assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[?(@.code == 'free')]").asArray().isNotEmpty();

        mvc.get().uri("/api/v1/plans").assertThat()
                .bodyJson()
                .extractingPath("$[?(@.code == 'pro')]").asArray().isNotEmpty();
    }

    @Test
    void excludesARetiredPlanFromTheListing() {
        Plan retired = new Plan(UUID.randomUUID(), "retired-" + UUID.randomUUID(), "Retired Plan");
        retired.retireForSignup();
        planRepository.saveAndFlush(retired);

        mvc.get().uri("/api/v1/plans").assertThat()
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[?(@.code == '" + retired.getCode() + "')]").asArray().isEmpty();
    }
}

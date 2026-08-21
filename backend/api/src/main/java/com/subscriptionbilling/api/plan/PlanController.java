package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.billingcore.plan.PlanCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public Plan catalog.
 */
@RestController
public class PlanController {

    private final PlanCatalogService planCatalogService;

    @Autowired
    public PlanController(PlanCatalogService planCatalogService) {
        this.planCatalogService = planCatalogService;
    }

    @GetMapping("/api/v1/plans")
    public List<PlanResponse> listPlans() {
        return planCatalogService.listAvailablePlans().stream()
                .map(PlanResponse::from)
                .toList();
    }
}

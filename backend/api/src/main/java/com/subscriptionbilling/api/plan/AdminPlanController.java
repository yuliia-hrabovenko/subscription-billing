package com.subscriptionbilling.api.plan;

import com.subscriptionbilling.api.error.CorrelationIds;
import com.subscriptionbilling.billingcore.plan.AdminPlanDetail;
import com.subscriptionbilling.billingcore.plan.PlanAdministrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Admin management of the Plan catalog: full visibility (including retired
 * Plans) plus create/retire/reprice. {@code principal.getName()} is the acting admin's
 * identity, threaded into {@link PlanAdministrationService} purely for its structured
 * logs — there's no admin-scoped audit table this writes to. Deliberately {@code
 * Principal}, not the raw {@code Jwt} (whose {@code sub} claim is an Auth0-opaque
 * id) — {@code Authentication.getName()} is what {@code
 * Auth0AdminJwtAuthenticationConverter} set to the token's human-readable {@code
 * preferred_username}.
 */
@RestController
@RequestMapping("/api/v1/admin/plans")
public class AdminPlanController {

    private final PlanAdministrationService planAdministrationService;

    @Autowired
    public AdminPlanController(PlanAdministrationService planAdministrationService) {
        this.planAdministrationService = planAdministrationService;
    }

    @GetMapping
    public List<AdminPlanSummaryResponse> listAll() {
        return planAdministrationService.listAll().stream().map(AdminPlanSummaryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AdminPlanDetailResponse getById(@PathVariable UUID id) {
        return AdminPlanDetailResponse.from(planAdministrationService.getById(id));
    }

    @PostMapping
    public ResponseEntity<AdminPlanDetailResponse> create(@RequestBody @Valid CreatePlanRequest request,
                                                            Principal principal) {
        AdminPlanDetail detail = planAdministrationService.createPlan(
                request.code(), request.name(), request.initialPrice(), principal.getName(), CorrelationIds.current());
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminPlanDetailResponse.from(detail));
    }

    @PostMapping("/{id}/retire")
    public AdminPlanDetailResponse retire(@PathVariable UUID id, Principal principal) {
        return AdminPlanDetailResponse.from(
                planAdministrationService.retirePlan(id, principal.getName(), CorrelationIds.current()));
    }

    @PostMapping("/{id}/price-versions")
    public AdminPlanDetailResponse addPriceVersion(@PathVariable UUID id, @RequestBody @Valid AddPriceVersionRequest request,
                                                     Principal principal) {
        AdminPlanDetail detail = planAdministrationService.addPriceVersion(
                id, request.amount(), request.effectiveFrom(), principal.getName(), CorrelationIds.current());
        return AdminPlanDetailResponse.from(detail);
    }
}

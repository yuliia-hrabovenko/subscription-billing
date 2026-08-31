package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.billingcore.customer.CustomerAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin visibility into Customers. There is no Customer-facing counterpart
 * to this controller — a Customer never lists or fetches other Customers, so unlike
 * {@code api.subscription}/{@code api.invoice}/{@code api.plan}, this package holds
 * only Admin endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerAdminService customerAdminService;

    @Autowired
    public AdminCustomerController(CustomerAdminService customerAdminService) {
        this.customerAdminService = customerAdminService;
    }

    @GetMapping
    public CustomerListResponse list(@RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) String limit) {
        return CustomerListResponse.from(customerAdminService.list(cursor, clamp(limit)));
    }

    @GetMapping("/{id}")
    public CustomerDetailResponse getById(@PathVariable UUID id) {
        return CustomerDetailResponse.from(customerAdminService.getById(id));
    }

    /** Falls back to {@link #DEFAULT_PAGE_SIZE} for anything blank, non-numeric, or below 1, rather than rejecting the request. */
    private static int clamp(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int parsed = Integer.parseInt(requested.trim());
            return parsed < 1 ? DEFAULT_PAGE_SIZE : Math.min(parsed, MAX_PAGE_SIZE);
        } catch (NumberFormatException notANumber) {
            return DEFAULT_PAGE_SIZE;
        }
    }
}

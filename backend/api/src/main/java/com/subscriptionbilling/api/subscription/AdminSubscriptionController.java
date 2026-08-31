package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin visibility into any Subscription by id — no ownership check, unlike {@link SubscriptionController}. */
@RestController
@RequestMapping("/api/v1/admin/subscriptions")
public class AdminSubscriptionController {

    private final SubscriptionService subscriptionService;

    @Autowired
    public AdminSubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/{id}")
    public AdminSubscriptionResponse getById(@PathVariable UUID id) {
        return AdminSubscriptionResponse.from(subscriptionService.getForAdmin(id));
    }
}

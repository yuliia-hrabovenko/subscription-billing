package com.subscriptionbilling.billingcore.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriceVersionRepository extends JpaRepository<PriceVersion, UUID> {

    List<PriceVersion> findByPlanId(UUID planId);
}

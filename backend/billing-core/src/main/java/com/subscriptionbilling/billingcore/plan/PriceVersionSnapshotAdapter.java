package com.subscriptionbilling.billingcore.plan;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshotPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * PriceVersionSnapshotPort} contract: looks a PriceVersion up directly by id rather than
 * by "the Plan's current price as of now", so a Plan price change after the PriceVersion
 * was charged can never change what a past receipt renders (Invariant 9).
 */
@Component
public class PriceVersionSnapshotAdapter implements PriceVersionSnapshotPort {

    private final PriceVersionRepository priceVersionRepository;

    public PriceVersionSnapshotAdapter(PriceVersionRepository priceVersionRepository) {
        this.priceVersionRepository = priceVersionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PriceVersionSnapshot describe(UUID priceVersionId) {
        PriceVersion priceVersion = priceVersionRepository.findById(priceVersionId)
                .orElseThrow(() -> new IllegalStateException("PriceVersion " + priceVersionId + " does not exist"));
        return new PriceVersionSnapshot(priceVersion.getPlan().getName(), priceVersion.getAmount());
    }
}

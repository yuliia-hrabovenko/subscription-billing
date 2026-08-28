package com.subscriptionbilling.billingjob.invoicing;

import java.math.BigDecimal;

/**
 * The Plan name and charged amount for one specific PriceVersion, as it was at the time
 * that PriceVersion was charged — never re-resolved against a Plan's current price.
 *
 * @param planName the owning Plan's name
 * @param amount   the amount fixed on this PriceVersion
 */
public record PriceVersionSnapshot(String planName, BigDecimal amount) {
}

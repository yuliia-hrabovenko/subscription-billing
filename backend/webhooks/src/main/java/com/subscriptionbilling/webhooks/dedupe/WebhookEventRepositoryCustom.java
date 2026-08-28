package com.subscriptionbilling.webhooks.dedupe;

/**
 * The write {@link WebhookEventRepository} needs beyond what {@link
 * org.springframework.data.jpa.repository.JpaRepository} already provides: an insert
 * that always runs in its own isolated, immediately-committed transaction, regardless
 * of whatever transaction the caller already has open.
 */
public interface WebhookEventRepositoryCustom {

    /**
     * Propagates a {@link org.springframework.dao.DataIntegrityViolationException} on a
     * unique-constraint violation rather than catching it, so its own isolated
     * transaction rolls back cleanly instead of being caught internally and left marked
     * rollback-only.
     *
     * @param event the record to insert
     */
    void insert(WebhookEvent event);
}

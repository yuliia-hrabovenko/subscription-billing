package com.subscriptionbilling.webhooks.dedupe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID>, WebhookEventRepositoryCustom {
}

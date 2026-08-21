package com.subscriptionbilling.audit;

import com.subscriptionbilling.audit.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AuditLogEntryRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Test
    void savesAndFetchesAnAuditLogEntryWithAllFields() {
        UUID entryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AuditLogEntry entry = new AuditLogEntry(
                entryId, subscriptionId, ActorType.CUSTOMER, "TRIALING", "ACTIVE", "req-correlation-123");

        auditLogEntryRepository.append(entry);

        Optional<AuditLogEntry> found = auditLogEntryRepository.findById(entryId);

        assertThat(found).isPresent();
        assertThat(found.get().getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(found.get().getActorType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(found.get().getOldState()).isEqualTo("TRIALING");
        assertThat(found.get().getNewState()).isEqualTo("ACTIVE");
        assertThat(found.get().getCorrelationId()).isEqualTo("req-correlation-123");
        assertThat(found.get().getOccurredAt()).isNotNull();
    }

    @Test
    void oldStateIsNullOnTheFirstEntryForASubscription() {
        UUID entryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AuditLogEntry entry = new AuditLogEntry(
                entryId, subscriptionId, ActorType.SYSTEM, null, "ACTIVE", "req-correlation-456");

        auditLogEntryRepository.append(entry);

        Optional<AuditLogEntry> found = auditLogEntryRepository.findById(entryId);

        assertThat(found).isPresent();
        assertThat(found.get().getOldState()).isNull();
    }
}

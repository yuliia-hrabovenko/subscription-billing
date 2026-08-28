package com.subscriptionbilling.webhooks.dedupe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class WebhookDedupeServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private WebhookEventRepository repository;

    private WebhookDedupeService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDedupeService(repository, FIXED_CLOCK);
    }

    @Test
    void theFirstRecordingOfAGatewayEventIdIsNew() {
        boolean isNew = service.recordIfNew("evt_1");

        assertThat(isNew).isTrue();
    }

    @Test
    void aGatewayEventIdThatLosesTheUniqueConstraintRaceIsADuplicateNotAnException() {
        doThrow(new DataIntegrityViolationException("duplicate key")).when(repository).insert(any());

        boolean isNew = service.recordIfNew("evt_1");

        assertThat(isNew).isFalse();
    }
}

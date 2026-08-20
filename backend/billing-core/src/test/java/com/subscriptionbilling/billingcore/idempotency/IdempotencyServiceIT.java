package com.subscriptionbilling.billingcore.idempotency;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Test
    void aKeyCanBeReusedAfterItsRetentionWindowExpires() throws InterruptedException {
        IdempotencyService shortRetentionService =
                new IdempotencyService(idempotencyKeyRepository, Clock.systemUTC(), Duration.ofMillis(50));
        UUID customerId = UUID.randomUUID();

        boolean first = shortRetentionService.recordIfNew(customerId, "cancel", "req-expiring");
        Thread.sleep(100);
        boolean afterExpiry = shortRetentionService.recordIfNew(customerId, "cancel", "req-expiring");

        assertThat(first).isTrue();
        assertThat(afterExpiry).isTrue();
    }

    @Test
    void firstUseOfAKeyIsNew() {
        UUID customerId = UUID.randomUUID();

        boolean isNew = idempotencyService.recordIfNew(customerId, "cancel", "req-1");

        assertThat(isNew).isTrue();
    }

    @Test
    void repeatingTheSameKeyForTheSameCustomerAndOperationIsADuplicate() {
        UUID customerId = UUID.randomUUID();

        boolean first = idempotencyService.recordIfNew(customerId, "cancel", "req-2");
        boolean second = idempotencyService.recordIfNew(customerId, "cancel", "req-2");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void theSameKeyValueIsNotSharedAcrossDifferentOperationsOrCustomers() {
        UUID customerId = UUID.randomUUID();

        boolean firstOperation = idempotencyService.recordIfNew(customerId, "cancel", "req-3");
        boolean differentOperation = idempotencyService.recordIfNew(customerId, "plan-change", "req-3");
        boolean differentCustomer = idempotencyService.recordIfNew(UUID.randomUUID(), "cancel", "req-3");

        assertThat(firstOperation).isTrue();
        assertThat(differentOperation).isTrue();
        assertThat(differentCustomer).isTrue();
    }

    @Test
    void aConcurrentRaceForTheSameKeyLetsExactlyOneCallerWin() throws InterruptedException {
        UUID customerId = UUID.randomUUID();
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch startingGate = new CountDownLatch(1);

        try {
            Callable<Boolean> attempt = () -> {
                startingGate.await();
                return idempotencyService.recordIfNew(customerId, "cancel", "req-race");
            };
            var futures = IntStream.range(0, attempts)
                    .mapToObj(i -> executor.submit(attempt))
                    .collect(toList());

            startingGate.countDown();
            long wins = futures.stream().map(this::resolve).filter(Boolean::booleanValue).count();

            assertThat(wins).isEqualTo(1);
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private boolean resolve(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

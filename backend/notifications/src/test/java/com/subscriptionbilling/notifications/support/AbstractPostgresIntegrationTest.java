package com.subscriptionbilling.notifications.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real Postgres instance (Flyway
 * migrations included) behind a full Spring context. Extend this from any module.
 *
 * <p>The container is deliberately NOT annotated {@code @Container}: that annotation
 * ties JUnit's Testcontainers extension to a single test class's lifecycle (started in
 * beforeAll, stopped in afterAll), which would tear it down after the first subclass
 * finishes and break every other subclass sharing this base. Starting it once in a
 * static initializer instead (the Testcontainers "singleton container" pattern) keeps
 * it running for every subclass in the JVM; Testcontainers' Ryuk reaper stops it when
 * the JVM exits.
 */

@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }
}

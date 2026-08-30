package com.subscriptionbilling.notifications.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Extends {@link AbstractPostgresIntegrationTest} with a real Kafka broker and Avro
 * schema registry, for tests that need to prove an actual publish -- not just the
 * relay's own poll/retry orchestration. Same "start once in a static initializer, never
 * torn down until the JVM exits" pattern as the Postgres container above, for the same
 * reason: a per-class {@code @Container} lifecycle would stop these after the first
 * subclass finishes.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractKafkaIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    protected static final GenericContainer<?> SCHEMA_REGISTRY =
            new GenericContainer<>(DockerImageName.parse("quay.io/apicurio/apicurio-registry:3.2.0"))
                    .withExposedPorts(8080, 9000)
                    .waitingFor(Wait.forHttp("/health/ready").forPort(9000).forStatusCode(200));

    static {
        KAFKA.start();
        SCHEMA_REGISTRY.start();
    }

    protected static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8080) + "/apis/registry/v3";
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "io.apicurio.registry.serde.avro.AvroKafkaSerializer");
        registry.add("spring.kafka.properties.apicurio.registry.url", AbstractKafkaIntegrationTest::schemaRegistryUrl);
        registry.add("spring.kafka.properties.apicurio.registry.auto-register", () -> "true");
    }
}

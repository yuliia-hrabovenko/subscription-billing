package com.subscriptionbilling.api;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for {@code main}'s {@code System.exit} handling itself: proves the web
 * server, launched the way its Docker image actually launches it ({@code java -cp ...
 * ApiApplication}), stays up past startup instead of exiting immediately. This needs a
 * real child JVM — the defect under test is {@code main} forcing an exit that calling
 * {@link ApiApplication#run} in-process (as {@link BillingJobEntryPointIT} does, for the
 * same "don't let main's System.exit kill the test JVM" reason documented there) would
 * never surface, since {@code run} itself was never the problem.
 *
 * <p>Readiness is read off the child's own stdout ("Started ApiApplication") rather than
 * by connecting to its HTTP port: the property under test is process lifetime, not
 * network reachability, and a log line needs nothing more than the child's stdout pipe.
 */
class ApiApplicationProcessIT extends AbstractPostgresIntegrationTest {

    @Test
    void webServerStaysUpAfterStartupInsteadOfExitingImmediately() throws Exception {
        Process process = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", System.getProperty("java.class.path"),
                "com.subscriptionbilling.api.ApiApplication",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        AtomicBoolean startedLogSeen = new AtomicBoolean(false);
        CountDownLatch startedLatch = new CountDownLatch(1);
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    if (line.contains("Started ApiApplication") && startedLogSeen.compareAndSet(false, true)) {
                        startedLatch.countDown();
                    }
                }
            } catch (IOException ignored) {
                // Stream closes when the process exits; nothing left to read.
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        try {
            boolean started = startedLatch.await(60, TimeUnit.SECONDS);
            assertThat(started)
                    .as("expected \"Started ApiApplication\" in child process output, got:%n%s", output)
                    .isTrue();

            Thread.sleep(2000);

            assertThat(process.isAlive())
                    .as("web server process should still be running 2s after startup, not exited via main()'s "
                            + "System.exit; output so far:%n%s", output)
                    .isTrue();
        } finally {
            process.destroyForcibly();
        }
    }
}

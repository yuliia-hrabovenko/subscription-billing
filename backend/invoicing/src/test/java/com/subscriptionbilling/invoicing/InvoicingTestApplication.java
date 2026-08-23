package com.subscriptionbilling.invoicing;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only {@code @SpringBootApplication} root so invoicing's own tests (a library
 * module with no runnable app of its own) can boot a full Spring context. Mirrors
 * notifications' {@code NotificationsTestApplication}.
 */
@SpringBootApplication
public class InvoicingTestApplication {
}

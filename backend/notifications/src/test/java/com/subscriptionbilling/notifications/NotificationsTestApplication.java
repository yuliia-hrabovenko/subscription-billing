package com.subscriptionbilling.notifications;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only {@code @SpringBootApplication} root so notifications' own tests (a library
 * module with no runnable app of its own) can boot a full Spring context. Mirrors
 * billing-core's {@code BillingCoreTestApplication}.
 */
@SpringBootApplication
public class NotificationsTestApplication {
}

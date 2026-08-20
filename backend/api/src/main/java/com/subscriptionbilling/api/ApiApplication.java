package com.subscriptionbilling.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The runnable Spring Boot application. Base package is {@code com.subscriptionbilling}
 * (not just {@code .api}) so component/entity/repository scanning picks up billing-core.
 */
@SpringBootApplication(scanBasePackages = "com.subscriptionbilling")
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}

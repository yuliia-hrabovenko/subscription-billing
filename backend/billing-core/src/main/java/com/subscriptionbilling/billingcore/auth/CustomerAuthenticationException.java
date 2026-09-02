package com.subscriptionbilling.billingcore.auth;

public class CustomerAuthenticationException extends RuntimeException {

    public CustomerAuthenticationException() {
        super("Invalid email or password");
    }
}

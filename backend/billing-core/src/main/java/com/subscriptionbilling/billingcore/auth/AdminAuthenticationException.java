package com.subscriptionbilling.billingcore.auth;

/**
 * The submitted admin username/password don't match the configured Admin identity.
 * Deliberately doesn't distinguish "unknown username" from "wrong
 * password" — same reasoning as this system's other authentication rejections: don't
 * give a caller a way to enumerate valid usernames.
 */
public class AdminAuthenticationException extends RuntimeException {

    public AdminAuthenticationException() {
        super("Invalid admin credentials");
    }
}

package com.subscriptionbilling.audit;

/**
 * Who/what triggered the Subscription state transition being recorded.
 */
public enum ActorType {
    SYSTEM,
    CUSTOMER,
    GATEWAY
}

# A dispute cancels the subscription immediately, with no recovery path

When the payment gateway reports a Dispute (chargeback) on a charge, we transition the Subscription straight to `canceled` — the same terminal state Dunning exhaustion reaches. We considered adding a separate recoverable `disputed` state that could revert to `active` if the dispute later resolves in the merchant's favor, but rejected it for v1.

This is a deliberate simplicity trade-off, consistent with the "no refunds, no admin override" decisions elsewhere in the system: it avoids a new lifecycle state and a "dispute resolved" webhook handler, at the cost of customer experience — a customer who wins a dispute has no automatic path back and must re-subscribe. Revisit this if dispute volume or customer complaints make the manual path untenable.

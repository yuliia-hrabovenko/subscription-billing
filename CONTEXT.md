# Subscription Billing Engine

The domain for a B2C, single-currency, flat-rate recurring billing system: subscribing customers to plans, running monthly charges, and handling payment failure.

## Language

### Core entities

**Customer**:
The individual who owns exactly one Subscription at a time and pays via a card on file.
_Avoid_: Account, User

**Plan**:
A named, priced billing offering (e.g. "Free", "Pro") that a Subscription references. Has a versioned price history — existing Subscriptions migrate to a new Price Version at their next Billing Cycle, not immediately. Can be closed to new signups (retired) while still billing the Subscriptions already on it — retiring a Plan is a visibility change, not a deletion.
_Avoid_: Tier, Package, Product

**Price Version**:
One effective-dated price in a Plan's history. A Plan price change creates a new Price Version rather than mutating the current one; an Invoice always references the Price Version that was in effect when it was charged, so a later price change never retroactively alters a past Invoice.
_Avoid_: Price (too vague — doesn't capture that it's one entry in a history)

**Subscription**:
The record tying a Customer to a Plan, tracking lifecycle state and — once on a paid Plan — the current Billing Cycle. A Customer has at most one *non-`canceled`* Subscription at a time, but may have several over their lifetime — reaching `canceled` is terminal for that Subscription; re-subscribing later creates a new one (no trial-eligibility or history carries over). Holds at most one pending plan change at a time; scheduling a new one replaces whatever was already pending rather than queuing behind it.
_Avoid_: Account, Membership

### Lifecycle states

A Subscription is in exactly one of:

- **trialing** — in its Trial window, access granted, no charge yet. Canceling from here goes straight to `canceled` — nothing's been charged yet, so there's no paid access to protect.
- **active** — on a paid or free Plan, current on payment (or free). The only state cancellation moves to `pending_cancellation` from.
- **pending_cancellation** — access continues (cancel-at-period-end); reverts to `active` if the Customer undoes the cancellation before the Billing Cycle ends. Only reachable from `active`.
- **suspended** — first Dunning attempt has failed; access is cut off immediately, not after the retry window. Reverts to `active` on a successful Payment Attempt, whether that's a scheduled Dunning retry or one the Customer triggers themselves after updating their card. Canceling from here goes straight to `canceled` — access is already gone, so there's nothing left to protect.
  _Avoid_: past_due (implies a grace period this system doesn't have)
- **canceled** — terminal. Reached via `pending_cancellation` running out, exhausted Dunning, a Dispute, or a direct cancellation from `trialing` or `suspended`.

### Billing cycle

**Billing Cycle**:
The recurring monthly period between one charge and the next. Only exists for a Subscription on a paid Plan — a free Subscription has none. Starts fresh, anchored to that day, the moment a Subscription first becomes paid (trial conversion, or an immediate free→paid upgrade).
_Avoid_: Period, Term

**Anchor Date**:
The day-of-month a paid Subscription's Billing Cycle recurs on, clamped to the last valid day of shorter months (a Jan 31 signup bills Feb 28/29, then Mar 31...). Meaningless for a free Subscription.
_Avoid_: Renewal date

**Trial**:
A time-limited period before a Subscription's first charge. Optional — a Customer may sign up for a paid Plan directly into `active` with an immediate first charge instead. When used, requires a payment method on file at signup; auto-converts to `active` (paid) when it ends unless canceled first. A failed conversion charge follows the normal Dunning flow — no special-cased "first charge" handling.
_Avoid_: Free trial, Demo

### Payments

**Invoice**:
The record of a Billing Cycle's charge — exactly one per Billing Cycle, regardless of how many Payment Attempts it took to resolve — together with its downloadable PDF receipt once paid.
_Avoid_: Bill — Receipt is the PDF artifact of an Invoice, not a separate concept.

**Payment Attempt**:
A single try at charging the Customer's card against an Invoice. An Invoice has one Payment Attempt on the happy path, or up to four (initial + three Dunning retries) when the card is declined.
_Avoid_: Charge, Transaction — those name the gateway-side action; Payment Attempt is this system's record of it.

**Dunning**:
The automated retry process (three further Payment Attempts over ~7 days) triggered when an Invoice's initial charge fails, ending in either a successful Payment Attempt (subscription back to `active`) or cancellation.
_Avoid_: Retry logic, Collections

**Dispute** (a.k.a. chargeback):
A gateway-reported challenge to a charge, initiated by the Customer's bank rather than the Customer directly through the product. Immediately moves the Subscription to `canceled` — the same terminal state Dunning exhaustion reaches, with no separate recovery path if the Dispute is later resolved in the merchant's favor.
_Avoid_: Chargeback as a distinct state — it is a trigger, not a lifecycle state.

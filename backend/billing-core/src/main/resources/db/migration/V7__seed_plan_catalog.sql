-- Initial Plan catalog: Free and one paid plan (Pro), each with a starting PriceVersion.
INSERT INTO plan (id, code, name, retired_for_signup, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'free', 'Free', FALSE, now()),
       ('00000000-0000-0000-0000-000000000002', 'pro', 'Pro', FALSE, now());

INSERT INTO price_version (id, plan_id, amount, effective_from, created_at)
VALUES ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 0.00,
        '2026-01-01T00:00:00Z', now()),
       ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000002', 19.00,
        '2026-01-01T00:00:00Z', now());

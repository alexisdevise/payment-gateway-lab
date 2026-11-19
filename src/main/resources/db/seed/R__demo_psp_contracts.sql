-- Demo data, only loaded by the "local" profile and by integration tests (spring.flyway.locations).
INSERT INTO psp_contract (id, merchant_id, psp_code, merchant_account_ref, priority, active)
VALUES ('00000000-0000-0000-0000-000000000001', 'merchant-demo',  'SANDBOX', 'sandbox-acct-demo',  10, TRUE),
       ('00000000-0000-0000-0000-000000000002', 'merchant-other', 'SANDBOX', 'sandbox-acct-other', 10, TRUE)
ON CONFLICT (merchant_id, psp_code) DO NOTHING;

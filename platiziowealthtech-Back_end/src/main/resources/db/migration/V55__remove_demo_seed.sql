-- =============================================================================
-- DF-08: remove the V3 demo seed from production.
--
-- V3__seed_data.sql inserts demo rows (Bob investor + bank + 5 orders +
-- redemption + notification + Charlie lead/interaction + a signup audit + the
-- a@a.com Test Distributor) on EVERY profile, so they would reach production.
-- This forward migration DELETEs exactly those rows by their fixed UUIDs, in FK
-- order (children first). We KEEP the canonical Alice distributor
-- (d9b2d63d-...) and the three product_schemes (a123/a222/a333).
--
-- Local/demo dev is unaffected: DemoDataSeeder (@Profile({"local","demo"}),
-- runs AFTER Flyway) re-inserts the same rows idempotently. Production has no
-- seeder, so it stays clean. We do NOT edit V3 in place (checksum) — this is a
-- forward DELETE.
-- =============================================================================

-- children of the Bob investor / his lumpsum order
DELETE FROM redemption_records  WHERE id = 'f123fe1c-7440-42f0-9ef2-5b9db0123456'::uuid;
DELETE FROM transaction_orders  WHERE id IN (
    'e012fe1c-7440-42f0-9ef2-5b9db054e012'::uuid,
    'e111fe1c-7440-42f0-9ef2-5b9db054e111'::uuid,
    'e222fe1c-7440-42f0-9ef2-5b9db054e222'::uuid,
    'e333fe1c-7440-42f0-9ef2-5b9db054e333'::uuid,
    'e444fe1c-7440-42f0-9ef2-5b9db054e444'::uuid
);
DELETE FROM notifications        WHERE id = '8fa1ae9b-0000-4b2a-8cfa-5b9d12341234'::uuid;
DELETE FROM investor_bank_accounts WHERE id = 'c34b1793-1b91-4df2-8c44-d8bc289b535d'::uuid;

-- the Bob investor
DELETE FROM investors            WHERE id = 'b75fef2e-7440-42f0-9ef2-5b9db054e526'::uuid;

-- Charlie lead + its interaction (interaction references the lead)
DELETE FROM lead_interactions    WHERE id = '11112222-3333-4444-5555-666677778888'::uuid;
DELETE FROM investor_leads       WHERE id = 'ca123eb4-0000-4123-85af-bbbbccccdddd'::uuid;

-- the demo signup audit event
DELETE FROM audit_events         WHERE id = 'aaaaabbb-cccc-dddd-eeee-ffff00001111'::uuid;

-- the a@a.com Test Distributor (no V3 rows reference it; Bob belongs to Alice)
DELETE FROM distributors         WHERE id = '4317cfd2-a41f-4320-a5dc-26835c7210ac'::uuid;

-- The two extra demo schemes V3 inserts with gen_random_uuid() (so they have no
-- fixed UUID) — target them by their deterministic external_scheme_code. They are
-- demo clutter (not the 3 canonical schemes a123/a222/a333) and have no orders
-- referencing them. In prod the real Cybrilla catalogue never carries these codes;
-- on local/demo the mock catalogue re-creates them on demand, so this is safe.
DELETE FROM product_schemes      WHERE external_scheme_code IN ('MF-201', 'SIF-301');

-- MVP-B4: Remove all demo seed data so production deploys start clean.
--
-- V1, V10 and V23 still INSERT Bob Investor, his bank account, the 5
-- transaction orders (1 lumpsum + 4 SIPs), the redemption record, the
-- notification, the Charlie lead + interaction, the audit event, and the
-- a@a.com Test Distributor. Those rows are demo-only pollution that should
-- never reach a real production database.
--
-- We can't edit those earlier migrations (Flyway tracks their checksums and
-- they are already applied on every environment). Instead this migration
-- DELETEs the rows so a fresh boot after V34 runs leaves a clean DB.
--
-- For local development, com.platizio.wealthtech.init.DemoDataSeeder (a
-- Spring CommandLineRunner gated by @Profile("local")) re-INSERTs the same
-- rows after migrations finish, preserving the demo state on localhost.
--
-- Rows we intentionally KEEP (not pollution):
--   • distributors d9b2d63d-... (Alice / alice@example.com) — canonical
--     demo distributor, referenced by tests, frontend demo login, and the
--     dashboard.
--   • product_schemes a123fef2-... (MF-SG-100), a222fef2-... (MF-BA-200),
--     a333fef2-... (SIF-SV-300) — the fund catalogue. V33 also seeds NAV
--     values against these schemes.
--
-- Deletes are ordered children-first to satisfy FK constraints. The deletes
-- are idempotent: re-running this against a DB where the rows are already
-- gone is a no-op.

-- ─────────────────────────────────────────────────────────────────────────
-- 1. Children of transaction_orders (redemption_records points at order_id)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM redemption_records
 WHERE id = 'f123fe1c-7440-42f0-9ef2-5b9db0123456';

-- ─────────────────────────────────────────────────────────────────────────
-- 2. Children of investor_leads (lead_interactions references lead_id)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM lead_interactions
 WHERE id = '11112222-3333-4444-5555-666677778888';

-- ─────────────────────────────────────────────────────────────────────────
-- 3. Charlie lead (no FK back to investors / distributors that blocks)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM investor_leads
 WHERE id = 'ca123eb4-0000-4123-85af-bbbbccccdddd';

-- ─────────────────────────────────────────────────────────────────────────
-- 4. Notification + audit event (children of distributors / investors)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM notifications
 WHERE id = '8fa1ae9b-0000-4b2a-8cfa-5b9d12341234';

DELETE FROM audit_events
 WHERE id = 'aaaaabbb-cccc-dddd-eeee-ffff00001111';

-- ─────────────────────────────────────────────────────────────────────────
-- 5. All Bob-attached transaction_orders
--    (V1 lumpsum + V10 four SIPs — must precede the investor delete)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM transaction_orders
 WHERE id IN (
    'e012fe1c-7440-42f0-9ef2-5b9db054e012',
    'e111fe1c-7440-42f0-9ef2-5b9db054e111',
    'e222fe1c-7440-42f0-9ef2-5b9db054e222',
    'e333fe1c-7440-42f0-9ef2-5b9db054e333',
    'e444fe1c-7440-42f0-9ef2-5b9db054e444'
 );

-- ─────────────────────────────────────────────────────────────────────────
-- 6. Bob's bank account, then Bob himself
--    (investor_documents from V27 cascade on investor delete, so no
--     explicit child delete needed there.)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM investor_bank_accounts
 WHERE id = 'c34b1793-1b91-4df2-8c44-d8bc289b535d';

DELETE FROM investors
 WHERE id = 'b75fef2e-7440-42f0-9ef2-5b9db054e526';

-- ─────────────────────────────────────────────────────────────────────────
-- 7. Test Distributor seeded by V23 (email a@a.com)
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM distributors
 WHERE id = '4317cfd2-a41f-4320-a5dc-26835c7210ac';

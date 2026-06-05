-- MVP-B5: An earlier revision of DemoDataSeeder (introduced in MVP-B4)
-- re-inserted Bob's five transaction orders and the redemption record using
-- the same fabricated sequential UUIDs that V1 and V10 originally seeded
-- ('e012fe1c-...', 'e111fe1c-...', 'e222fe1c-...', 'e333fe1c-...',
-- 'e444fe1c-...', 'f123fe1c-...'). The frontend renders order IDs as
-- TXN-{first6-of-id}, so those leaked into the Transactions and Recent
-- Activity screens as obviously fake "TXN-e012fe / TXN-e111fe / …".
--
-- DemoDataSeeder now uses realistic v4-shaped UUIDs. This migration removes
-- the legacy patterned rows so a clean re-seed can happen on next boot. The
-- DELETEs are idempotent — they no-op if the legacy rows are already gone
-- (e.g., a fresh deploy where DemoDataSeeder never used the old IDs).

-- Children first (redemption references the lumpsum order).
DELETE FROM redemption_records WHERE id = 'f123fe1c-7440-42f0-9ef2-5b9db0123456';

DELETE FROM transaction_orders WHERE id IN (
  'e012fe1c-7440-42f0-9ef2-5b9db054e012',
  'e111fe1c-7440-42f0-9ef2-5b9db054e111',
  'e222fe1c-7440-42f0-9ef2-5b9db054e222',
  'e333fe1c-7440-42f0-9ef2-5b9db054e333',
  'e444fe1c-7440-42f0-9ef2-5b9db054e444'
);

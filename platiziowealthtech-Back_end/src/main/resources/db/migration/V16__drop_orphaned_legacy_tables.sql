-- B-20: drop legacy tables created in V1 but never wired to a JPA entity.
--
-- Verified before writing this migration:
--   • Reconciled the table set in V1 (18 CREATE TABLE statements) against
--     every @Table(name=...) annotation in
--     com.platizio.wealthtech.domain — 10 V1 tables are owned by entities;
--     these 8 are not.
--   • Grepped the entire src/ tree for SQL-context references
--     (FROM|JOIN|INTO|UPDATE|DELETE FROM|REFERENCES) to each of these names.
--     The ONLY matches are inside V1 itself (the original CREATE blocks
--     plus the unused SELECT * debug lines and internal legacy FKs).
--   • The ticket states "9 orphaned legacy tables" — the actual count is 8.
--
-- ⚠ PRE-DEPLOYMENT VERIFICATION (cannot be run from source; needs a live DB):
-- Run this against each environment before applying V16 to confirm nothing
-- external to this repo (BI tools, ad-hoc reports, external integrations)
-- still has FK references into these tables:
--
--   SELECT tc.table_name AS dependent_table,
--          kcu.column_name,
--          ccu.table_name  AS referenced_legacy_table
--   FROM information_schema.table_constraints       AS tc
--   JOIN information_schema.key_column_usage        AS kcu
--     ON tc.constraint_name = kcu.constraint_name
--   JOIN information_schema.constraint_column_usage AS ccu
--     ON ccu.constraint_name = tc.constraint_name
--   WHERE tc.constraint_type = 'FOREIGN KEY'
--     AND ccu.table_name IN (
--       'kyc_records','documents','bank_accounts','fund_schemes',
--       'investment_accounts','orders','payments','api_logs'
--     )
--     AND tc.table_name NOT IN (
--       'kyc_records','documents','bank_accounts','fund_schemes',
--       'investment_accounts','orders','payments','api_logs'
--     );
--
-- If that query returns ANY rows, do not run V16 — the legacy table is still
-- referenced from a non-legacy table and dropping it would orphan FK data.
-- The internal legacy↔legacy FKs (payments→orders, orders→investment_accounts,
-- orders→fund_schemes) are intentional and handled by CASCADE below.

-- IF EXISTS keeps the migration idempotent if a table was already removed
-- out-of-band. CASCADE walks the internal legacy FK web (payments→orders,
-- orders→investment_accounts, orders→fund_schemes) so the order below is
-- documentation, not a correctness requirement.

DROP TABLE IF EXISTS payments            CASCADE;
DROP TABLE IF EXISTS orders              CASCADE;
DROP TABLE IF EXISTS investment_accounts CASCADE;
DROP TABLE IF EXISTS fund_schemes        CASCADE;
DROP TABLE IF EXISTS kyc_records         CASCADE;
DROP TABLE IF EXISTS documents           CASCADE;
DROP TABLE IF EXISTS bank_accounts       CASCADE;
DROP TABLE IF EXISTS api_logs            CASCADE;

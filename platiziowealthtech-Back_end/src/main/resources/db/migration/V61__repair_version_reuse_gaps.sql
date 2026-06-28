-- =============================================================================
-- Repair migration-version reuse gaps on existing local/demo databases.
--
-- Some environments already recorded older V52/V53 scripts before those version
-- numbers were reused. Flyway cannot re-run a version that is already marked
-- successful, so this forward migration repeats the current idempotent changes.
-- =============================================================================

ALTER TABLE investors
  ADD COLUMN IF NOT EXISTS email_verified             BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS email_verified_at          TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS email_verification_method  VARCHAR(16),
  ADD COLUMN IF NOT EXISTS email_belongs_to           VARCHAR(24),
  ADD COLUMN IF NOT EXISTS mobile_verified            BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS mobile_verified_at         TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS mobile_verification_method VARCHAR(16),
  ADD COLUMN IF NOT EXISTS mobile_belongs_to          VARCHAR(24);

UPDATE product_schemes ps
SET metadata_json = jsonb_set(
        coalesce(ps.metadata_json::jsonb, '{}'::jsonb),
        '{nav}',
        to_jsonb(v.nav),
        true
    )::text,
    updated_at = now()
FROM (VALUES
    ('MF-SG-100',  52.34::numeric),
    ('MF-BA-200',  18.90::numeric),
    ('SIF-SV-300', 24.65::numeric),
    ('MF-201',     21.40::numeric),
    ('SIF-301',    15.75::numeric)
) AS v(code, nav)
WHERE ps.external_scheme_code = v.code
  AND NOT jsonb_exists(coalesce(ps.metadata_json::jsonb, '{}'::jsonb), 'nav');

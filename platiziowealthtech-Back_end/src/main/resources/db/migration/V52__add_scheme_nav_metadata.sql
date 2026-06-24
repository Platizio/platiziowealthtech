-- =============================================================================
-- DF-07: seed a static NAV into each seeded scheme's metadata_json.
--
-- ProductScheme has no nav column; the distributor UI reads nav / current_nav /
-- last_nav out of the parsed metadata_json. The V3 seed only carried "returns",
-- so every screen rendered NAV as Rs 0.00. This forward migration adds a numeric
-- "nav" key for the seeded schemes on both fresh and already-migrated databases
-- (we do NOT edit the applied V3 migration — that would break its checksum).
--
-- metadata_json is a TEXT column, so we cast to jsonb, set the key, and cast
-- back. coalesce(...,'{}') handles rows whose metadata_json is NULL, and the
-- jsonb_exists guard makes this idempotent and prevents clobbering a real NAV
-- that a later live-catalogue sync may have written.
--
-- These are STATIC demo values, not live pricing. Live NAV comes from the
-- catalogue sync (RealCybrillaClient.buildSchemeMetadata) / an AMFI feed.
-- =============================================================================
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

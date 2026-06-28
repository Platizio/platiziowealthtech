-- =============================================================================
-- Tier 2: investor email + mobile contact verification / self-declaration.
--
-- Each channel can be satisfied two ways: an OTP round-trip (via Supabase Auth)
-- or distributor self-declaration. Both write *_verified / *_verified_at /
-- *_verification_method (OTP | SELF_DECLARED) and *_belongs_to (self | spouse |
-- dependent_child | dependent_parent | guardian — the FP belongs_to enum). The
-- declared belongs_to is then forwarded to FP instead of today's hardcoded
-- "self".
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

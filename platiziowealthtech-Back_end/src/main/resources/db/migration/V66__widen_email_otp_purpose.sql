-- email_otps.purpose was VARCHAR(20), sized for TRANSACTION_APPROVAL (exactly 20 chars).
-- The newer PROFILE_CHANGE_APPROVAL purpose (23 chars, investor.md M4/R9) overflowed it,
-- failing the approval-OTP insert. Widen to comfortably fit current + future OtpPurpose values.
ALTER TABLE email_otps ALTER COLUMN purpose TYPE VARCHAR(48);

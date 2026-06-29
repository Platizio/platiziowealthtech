-- email_otps.purpose was VARCHAR(20) (V6), sized for TRANSACTION_APPROVAL (exactly 20 chars).
-- The merged OtpPurpose enum keeps PROFILE_CHANGE_APPROVAL (23 chars), persisted by
-- OtpService for distributor-proposed profile-change approvals. It overflows VARCHAR(20),
-- failing the approval-OTP insert. Widen to comfortably fit current + future values.
ALTER TABLE email_otps ALTER COLUMN purpose TYPE VARCHAR(48);

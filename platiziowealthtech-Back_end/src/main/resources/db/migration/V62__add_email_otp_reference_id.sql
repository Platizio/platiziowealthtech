-- Binds an email OTP to a specific subject (e.g. a transaction_approval_challenges row)
-- so two concurrent challenges sharing the same (email, purpose) cannot cross-consume
-- each other's code. NULL for login/signup flows, where purpose alone isolates the code.
alter table email_otps add column reference_id uuid;

create index idx_email_otps_email_purpose_reference
    on email_otps (email, purpose, reference_id);

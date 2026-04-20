-- Add password_hash column to distributors for JWT auth
ALTER TABLE distributors ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

-- Set default BCrypt hash of 'password123' for existing rows (for testing only)
-- BCrypt hash: $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyEMDdEcS
UPDATE distributors SET password_hash = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyEMDdEcS' WHERE password_hash IS NULL;

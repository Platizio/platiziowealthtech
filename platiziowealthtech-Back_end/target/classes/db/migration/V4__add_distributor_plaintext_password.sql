-- Add plaintext password column to distributors for frontend mock login
ALTER TABLE distributors ADD COLUMN IF NOT EXISTS password VARCHAR(255);

-- Set default plaintext password of 'password123' for existing rows
UPDATE distributors SET password = 'password123' WHERE password IS NULL;

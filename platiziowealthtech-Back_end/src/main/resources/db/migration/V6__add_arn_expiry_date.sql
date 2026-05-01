ALTER TABLE distributors ADD COLUMN arn_expiry_date DATE;
UPDATE distributors SET arn_expiry_date = '2025-12-31'; -- Default for existing
UPDATE distributors SET arn_expiry_date = '2023-12-31' WHERE email = 'aditya@apexwealth.in'; -- Make one expired for testing

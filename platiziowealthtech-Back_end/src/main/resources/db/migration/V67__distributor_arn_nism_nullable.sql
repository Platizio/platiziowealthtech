-- Distributor registration now collects only basic identity (name, contact, email, password);
-- ARN + NISM (and bank/compliance) are completed later inside the dashboard. They must therefore
-- be nullable at signup time. Existing rows keep their values; only the NOT NULL constraint is dropped.
ALTER TABLE distributors ALTER COLUMN arn_number DROP NOT NULL;
ALTER TABLE distributors ALTER COLUMN nism_certificate_number DROP NOT NULL;

-- Align old seeded distributor password hashes with the frontend demo password.
UPDATE distributors
SET password_hash = '$2a$10$apgfh87uLrmET8uOKEEtkuBAKLY6pU8MIJ9HCp5qK2jiLIr1qjxBm'
WHERE password_hash = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyEMDdEcS';


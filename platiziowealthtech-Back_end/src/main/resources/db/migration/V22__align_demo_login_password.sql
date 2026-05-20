-- Keep the frontend demo credentials working after password-hash hardening.
-- Demo login shown in LoginPage.tsx:
--   email: alice@example.com
--   password: Platizio@2024
UPDATE distributors
SET password_hash = '$2a$10$Sx5yk6Ww1cJ2OlRKx4TsGeubh4LN3MvNxjPlfiiCoqvSCpzR0Rh8.'
WHERE email = 'alice@example.com'
   OR password_hash IN (
        '$2a$10$apgfh87uLrmET8uOKEEtkuBAKLY6pU8MIJ9HCp5qK2jiLIr1qjxBm',
        '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyEMDdEcS'
   );

-- Add realistic product schemes for MF and SIF
INSERT INTO product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active)
VALUES 
('a222fef2-7440-42f0-9ef2-5b9db054a222', now(), now(), 'Balanced Advantage Fund', 'Platizio AMC', 'MF', 'MF-BA-200', 'INF000000002', 'MUTUAL_FUND', true),
('a333fef2-7440-42f0-9ef2-5b9db054a333', now(), now(), 'Social Venture Fund', 'Impact AMC', 'SIF', 'SIF-SV-300', 'INF000000003', 'SIF', true);

-- Add dummy SIP orders for the dashboard
-- Active MF SIP
INSERT INTO transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode, mandate_mode, product_category)
VALUES ('e111fe1c-7440-42f0-9ef2-5b9db054e111', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'COMPLETED', 5000.00, 50.0000, 'NET_BANKING', 'E-Mandate', 'MF');

-- Active SIF SIP
INSERT INTO transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode, mandate_mode, product_category)
VALUES ('e222fe1c-7440-42f0-9ef2-5b9db054e222', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'COMPLETED', 25000.00, 250.0000, 'NACH', 'Physical Mandate', 'SIF');

-- Failed SIP
INSERT INTO transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode, mandate_mode, product_category)
VALUES ('e333fe1c-7440-42f0-9ef2-5b9db054e333', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'FAILED', 10000.00, 0, 'NET_BANKING', 'E-Mandate', 'MF');

-- Paused SIP (Draft status maps to Paused in our logic)
INSERT INTO transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode, mandate_mode, product_category)
VALUES ('e444fe1c-7440-42f0-9ef2-5b9db054e444', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'DRAFT', 15000.00, 0, 'NET_BANKING', 'E-Mandate', 'SIF');

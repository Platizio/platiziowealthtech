-- Add more product schemes for SIF and MUTUAL_FUND categories
INSERT INTO product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active)
VALUES (gen_random_uuid(), now(), now(), 'Balanced Mutual Fund', 'Platizio Assets', 'MUTUAL_FUND', 'MF-201', 'INF002', 'MUTUAL_FUND', true)
ON CONFLICT (external_scheme_code) DO NOTHING;

INSERT INTO product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active)
VALUES (gen_random_uuid(), now(), now(), 'Social Impact Fund', 'Impact Capital', 'SIF', 'SIF-301', 'INF003', 'SIF', true)
ON CONFLICT (external_scheme_code) DO NOTHING;

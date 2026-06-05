-- Seed NAV and complete returns (incl. 3y) in metadata_json for all five product schemes so frontend can render real NAV/returns instead of zeros.
UPDATE product_schemes
SET metadata_json = '{"nav":54.32,"returns":{"daily":0.2,"ytd":8.1,"1y":12.4,"3y":35.2,"5y":55.8}}'
WHERE external_scheme_code = 'MF-201';

UPDATE product_schemes
SET metadata_json = '{"nav":28.71,"returns":{"daily":-0.1,"ytd":5.4,"1y":8.2,"3y":21.5,"5y":32.1}}'
WHERE external_scheme_code = 'SIF-301';

UPDATE product_schemes
SET metadata_json = '{"nav":142.85,"returns":{"daily":0.8,"ytd":12.4,"1y":18.5,"3y":58.7,"5y":85.2}}'
WHERE external_scheme_code = 'MF-SG-100';

UPDATE product_schemes
SET metadata_json = '{"nav":38.16,"returns":{"daily":0.3,"ytd":6.8,"1y":11.2,"3y":28.4,"5y":42.5}}'
WHERE external_scheme_code = 'MF-BA-200';

UPDATE product_schemes
SET metadata_json = '{"nav":24.45,"returns":{"daily":-0.2,"ytd":4.1,"1y":9.6,"3y":19.2,"5y":28.4}}'
WHERE external_scheme_code = 'SIF-SV-300';

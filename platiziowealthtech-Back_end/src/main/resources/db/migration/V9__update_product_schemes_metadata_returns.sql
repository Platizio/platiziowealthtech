-- Update metadata_json for existing product schemes to include returns mock data
UPDATE product_schemes
SET metadata_json = '{"returns":{"daily":0.2,"ytd":8.1,"1y":12.4,"5y":55.8}}'
WHERE external_scheme_code = 'MF-201';

UPDATE product_schemes
SET metadata_json = '{"returns":{"daily":-0.1,"ytd":5.4,"1y":8.2,"5y":32.1}}'
WHERE external_scheme_code = 'SIF-301';

UPDATE product_schemes
SET metadata_json = '{"returns":{"daily":0.8,"ytd":12.4,"1y":18.5,"5y":85.2}}'
WHERE external_scheme_code = 'MF-SG-100';

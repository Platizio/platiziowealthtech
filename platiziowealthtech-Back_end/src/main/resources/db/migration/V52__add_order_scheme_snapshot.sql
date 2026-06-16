alter table transaction_orders
    add column if not exists product_scheme_name varchar(255),
    add column if not exists product_scheme_external_code varchar(255),
    add column if not exists product_scheme_isin varchar(100),
    add column if not exists product_scheme_amc_name varchar(255);

update transaction_orders o
   set product_scheme_name = coalesce(o.product_scheme_name, p.scheme_name),
       product_scheme_external_code = coalesce(o.product_scheme_external_code, p.external_scheme_code),
       product_scheme_isin = coalesce(o.product_scheme_isin, p.external_isin),
       product_scheme_amc_name = coalesce(o.product_scheme_amc_name, p.amc_name),
       product_category = coalesce(o.product_category, p.category)
  from product_schemes p
 where o.product_scheme_id = p.id
   and (o.product_scheme_name is null
        or o.product_scheme_external_code is null
        or o.product_scheme_isin is null
        or o.product_scheme_amc_name is null
        or o.product_category is null);

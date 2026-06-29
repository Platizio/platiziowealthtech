-- Contract-note / allotment details on completed MF transactions (compliance).
-- Populated only when an order is actually allotted (SUCCESSFUL/COMPLETED); left
-- null otherwise. net_invested is derived in the DTO (amount - stamp_duty), not stored.
alter table transaction_orders
    add column if not exists allotment_nav  numeric(20,4),
    add column if not exists allotment_date timestamptz,
    add column if not exists stamp_duty     numeric(20,4),
    add column if not exists folio_number   varchar(64);

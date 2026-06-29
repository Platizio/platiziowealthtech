-- Nominee reconciliation: the team's IRIS `investor_nominees` table is canonical, and
-- Sumit's investor self-service nominee flow (Nominee entity → NomineeService →
-- InvestorPortalController /nominations) is grafted ONTO that same table rather than a
-- second nominee table. Sumit's thinner model carries three fields the IRIS columns don't
-- (a single free-text address line, an integer allocation %, and a minor's guardian name),
-- so we add them as nullable — IRIS rows simply leave them null, self-service rows leave the
-- IRIS-only columns (share_percent, address_line1..3, etc.) null. One table, both flows.
alter table investor_nominees
    add column if not exists allocation_percentage integer,
    add column if not exists address_line          varchar(160),
    add column if not exists guardian_name         varchar(160);

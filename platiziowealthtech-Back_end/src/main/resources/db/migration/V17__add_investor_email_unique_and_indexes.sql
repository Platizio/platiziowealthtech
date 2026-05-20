-- B-18: Add UNIQUE constraint on investors.email.
-- The original V1 DDL had email as NOT NULL but without a uniqueness guarantee,
-- allowing duplicate email addresses to be inserted.

ALTER TABLE investors
    ADD CONSTRAINT uq_investor_email UNIQUE (email);

-- Supporting indexes for the most frequent query patterns.
-- PostgreSQL does NOT auto-create indexes for non-PK FK columns, so
-- findByDistributorId (the most-called repo method) was doing a full table scan.
CREATE INDEX IF NOT EXISTS idx_investors_distributor_id
    ON investors (distributor_id);

CREATE INDEX IF NOT EXISTS idx_investors_kyc_status
    ON investors (kyc_status);

-- ─────────────────────────────────────────────────────────────────────────────
-- B-19: Index FK columns to eliminate sequential scans on every join/lookup.
--
-- NOTE on table names: the live application maps its JPA entities to
-- `transaction_orders`, `investor_leads`, `notifications` and `audit_events`.
-- The V1 DDL also created legacy `orders`/`payments`/etc. tables that have NO
-- JPA entity and are never queried — those are intentionally NOT indexed here.
-- ─────────────────────────────────────────────────────────────────────────────

-- transaction_orders: TransactionOrderRepository.findByInvestorId(...)
CREATE INDEX IF NOT EXISTS idx_tx_orders_investor_id
    ON transaction_orders (investor_id);

-- transaction_orders: composite covering BOTH
--   • findByDistributorId(...)                        — uses the leading column
--   • findByDistributorIdAndTransactionTypeAndCreatedAtAfter(...)  — the SIP
--     trend hot path. Column order is deliberate: equality predicates
--     (distributor_id, transaction_type) first, range predicate (created_at)
--     last, so Postgres can do an index range scan instead of a seq scan.
-- A separate distributor_id-only index is unnecessary — this composite's
-- leading-column prefix already serves plain distributor_id lookups.
CREATE INDEX IF NOT EXISTS idx_tx_orders_distributor_type_created
    ON transaction_orders (distributor_id, transaction_type, created_at);

-- investor_leads: InvestorLeadRepository.findByAssignedDistributorId(...)
-- (column is assigned_distributor_id — there is no plain distributor_id here)
CREATE INDEX IF NOT EXISTS idx_investor_leads_assigned_distributor_id
    ON investor_leads (assigned_distributor_id);

-- notifications: findByDistributorIdOrderByCreatedAtDesc(...)
-- Composite with created_at DESC lets the index satisfy the filter AND the
-- sort, avoiding a separate sort step on the result set.
CREATE INDEX IF NOT EXISTS idx_notifications_distributor_created
    ON notifications (distributor_id, created_at DESC);

-- audit_events: no repository query filters by actor_id today, but audit
-- records are routinely looked up per-actor for compliance/forensics and the
-- table is append-heavy and grows unbounded. Forward-looking index.
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_id
    ON audit_events (actor_id);

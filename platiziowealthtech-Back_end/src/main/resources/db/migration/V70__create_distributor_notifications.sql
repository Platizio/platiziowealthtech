-- R8 (linking choreography): Sumit's dedicated distributor notification store.
-- The `DistributorNotification` entity (domain/DistributorNotification.java) is wired through
-- DistributorNotificationService into DashboardController, but no migration ever created its
-- table — Sumit's branch ran under Hibernate auto-DDL, so the gap only surfaced under
-- ddl-auto=validate. This is the net-new, additive half of the merge (kept alongside the
-- team's shared `notifications` table, which is unrelated), so we forward-create it here.
-- Mirrors BaseEntity (id/created_at/updated_at) and the project's existing DDL conventions.
create table if not exists distributor_notifications (
    id              uuid          primary key,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    distributor_id  uuid          not null references distributors (id),
    investor_id     uuid          references investors (id),
    type            varchar(48)   not null,   -- DistributorNotificationType (enum string)
    title           varchar(255)  not null,
    body            varchar(1000),
    read_at         timestamptz
);

-- Backs findByDistributorIdOrderByCreatedAtDesc (the distributor notification feed).
create index if not exists idx_distributor_notifications_distributor_created
    on distributor_notifications (distributor_id, created_at desc);

-- Backs findByDistributorIdAndReadAtIsNullOrderByCreatedAtDesc (the unread badge/feed).
create index if not exists idx_distributor_notifications_unread
    on distributor_notifications (distributor_id)
    where read_at is null;

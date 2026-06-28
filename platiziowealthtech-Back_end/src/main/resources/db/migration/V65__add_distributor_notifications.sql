-- V65: distributor notifications (investor.md M1, R8). A lightweight in-app feed for
-- "investor approved", "form submitted", "form skipped", "edit approved", "link rejected".
create table if not exists distributor_notifications (
    id              uuid         primary key,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    distributor_id  uuid         not null,
    investor_id     uuid,
    type            varchar(48)  not null,   -- INVESTOR_APPROVED|INVESTOR_FORM_SUBMITTED|INVESTOR_SKIPPED|PROFILE_CHANGE_APPROVED|LINK_REJECTED
    title           varchar(255) not null,
    body            varchar(1000),
    read_at         timestamptz,
    constraint fk_dist_notif_distributor
        foreign key (distributor_id) references distributors (id),
    constraint fk_dist_notif_investor
        foreign key (investor_id) references investors (id)
);

create index if not exists idx_dist_notif_distributor
    on distributor_notifications (distributor_id, read_at);

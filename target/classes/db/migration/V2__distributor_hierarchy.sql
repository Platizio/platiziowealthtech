alter table distributors
    add column if not exists master_distributor_id uuid,
    add column if not exists role varchar(50) not null default 'MASTER_DISTRIBUTOR';

create unique index if not exists ux_distributors_e_uin_number
    on distributors (e_uin_number)
    where e_uin_number is not null;

create index if not exists ix_distributors_master_distributor_id
    on distributors (master_distributor_id);

alter table distributors
    add constraint fk_distributors_master_distributor
    foreign key (master_distributor_id) references distributors (id);

create index if not exists ix_investors_distributor_id
    on investors (distributor_id);

alter table investors
    add constraint fk_investors_distributor
    foreign key (distributor_id) references distributors (id);

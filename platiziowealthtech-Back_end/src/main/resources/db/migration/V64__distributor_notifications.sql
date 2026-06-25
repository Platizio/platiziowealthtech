-- PA6 (R8 receive-half): in-app distributor notifications for the investor↔distributor
-- linking choreography. The `notifications` table (V1__baseline_schema.sql) already carries
-- distributor_id + investor_id + type + title + message + read_flag, and the primary
-- (distributor_id, created_at desc) listing index already exists (V2__indexes.sql), so this
-- migration adds NO columns — it only adds the two indexes the receive-half queries need.

-- Backs NotificationRepository.countByDistributorIdAndReadFlagFalse (the unread badge),
-- which today scans by distributor_id and filters read_flag.
create index if not exists idx_notifications_distributor_unread
    on notifications (distributor_id)
    where read_flag = false;

-- The new INVESTOR_LINK_APPROVED / INVESTOR_FORM_SUBMITTED notifications are investor-scoped;
-- this supports investor-keyed lookups/joins without a full table scan.
create index if not exists idx_notifications_investor
    on notifications (investor_id)
    where investor_id is not null;

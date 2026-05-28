CREATE INDEX IF NOT EXISTS idx_investors_dashboard_status
    ON investors (distributor_id, kyc_status, bank_verification_status, created_at DESC)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_tx_orders_dashboard_failed
    ON transaction_orders (distributor_id, order_status, created_at DESC)
    WHERE is_deleted = false;

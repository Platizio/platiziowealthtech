ALTER TABLE transaction_orders
    ADD COLUMN IF NOT EXISTS investor_action_token VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_transaction_orders_investor_action_token
    ON transaction_orders (investor_action_token)
    WHERE investor_action_token IS NOT NULL;

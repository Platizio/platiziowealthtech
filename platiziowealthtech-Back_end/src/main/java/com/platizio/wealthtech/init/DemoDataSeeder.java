package com.platizio.wealthtech.init;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Re-inserts the demo seed rows that V34__remove_demo_seed.sql wipes out.
 *
 * <p>V1, V10 and V23 historically inserted demo data (Bob Investor, his bank
 * account, five transaction orders, a redemption, a notification, the
 * Charlie lead + interaction, the Alice signup audit event, and the
 * a@a.com Test Distributor) so the dashboard had something to show during
 * frontend development. That pollution must never reach production, so V34
 * deletes the rows in every environment after migrations run.
 *
 * <p>On local dev the developer still wants the demo state, so this
 * CommandLineRunner — gated by {@code @Profile("local")} — re-inserts the
 * exact same rows (same UUIDs as the originating migrations) using
 * {@code INSERT ... ON CONFLICT DO NOTHING}, making it idempotent across
 * boots.
 *
 * <p>Production deploys set {@code SPRING_PROFILES_ACTIVE=production}, so
 * this component is not instantiated there and the database stays clean.
 *
 * <p>Note on Alice (d9b2d63d-...) and the three product schemes: those are
 * canonical demo entities the codebase relies on (frontend demo login, NAV
 * seed in V33, etc.) and are NOT touched by V34 or this seeder.
 */
@Component
@Profile("local")
@Order(0)
public class DemoDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    public DemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        seedBobInvestor();
        seedBobBankAccount();
        seedBobLumpsumOrder();
        seedBobSipOrders();
        seedBobRedemption();
        seedBobNotification();
        seedCharlieLead();
        seedCharlieInteraction();
        seedAliceSignupAudit();
        upsertTestDistributor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bob Investor — child of Alice the demo distributor.
    // Column list mirrors V1 line 261 + the NOT NULL columns added later:
    //   • is_deleted (V15 — default FALSE)
    //   • relationship_type (V26 — default 'SELF')
    //   • household_id (V26 — backfilled to the investor's own id)
    // bank_verification_status was already NOT NULL in V1.
    // ─────────────────────────────────────────────────────────────────────
    private void seedBobInvestor() {
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, " +
            "  mobile_number, email, pan, date_of_birth, address_line1, " +
            "  city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, cybrilla_investor_id, " +
            "  is_deleted, relationship_type, household_id" +
            ") VALUES (" +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', now(), now(), " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', 'Bob Investor', " +
            "  '9988776655', 'bob@example.com', 'ABCDE1234F', '1990-01-01', " +
            "  '123 Main St', 'Mumbai', 'Maharashtra', '400001', 'ACTIVE', " +
            "  'COMPLETED', 'VERIFIED', 'MODERATE', 'CYB-INV-1', " +
            "  false, 'SELF', 'b75fef2e-7440-42f0-9ef2-5b9db054e526'" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 264 — Bob's bank account.
    private void seedBobBankAccount() {
        jdbc.update(
            "INSERT INTO investor_bank_accounts (" +
            "  id, created_at, updated_at, investor_id, account_holder_name, " +
            "  account_number, ifsc_code, bank_name, branch_name, " +
            "  verification_status" +
            ") VALUES (" +
            "  'c34b1793-1b91-4df2-8c44-d8bc289b535d', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'Bob', " +
            "  '555566667777', 'ICIC0000001', 'ICICI Bank', 'Main Branch', " +
            "  'VERIFIED'" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 270 — Bob's lumpsum order on MF-SG-100.
    // is_deleted (V15) is included explicitly as false.
    // ── MVP-B5: UUID changed from 'e012fe1c-...e012' to a realistic random one.
    // The FE renders order IDs as `TXN-${id.substring(0, 6)}` (see
    // Transactions.tsx:398), so the old patterned head ("e012fe", "e111fe",
    // …) leaked into the UI as TXN-e012fe / TXN-e111fe etc. — instantly
    // identifiable as fabricated. New UUIDs are v4-shaped and look random.
    private void seedBobLumpsumOrder() {
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, " +
            "  product_scheme_id, transaction_type, order_status, amount, " +
            "  units, payment_mode, is_deleted" +
            ") VALUES (" +
            "  '5d8a7c2e-3f9b-4861-94c7-2f6e8d1b3a05', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'a123fef2-7440-42f0-9ef2-5b9db054a123', 'LUMPSUM_PURCHASE', " +
            "  'COMPLETED', 10000.00, 100.0000, 'NET_BANKING', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V10 — four SIP orders (1 active MF, 1 active SIF, 1 failed, 1 draft).
    // ── MVP-B5: same UUID swap. See comment on seedBobLumpsumOrder.
    private void seedBobSipOrders() {
        // Active MF SIP
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, " +
            "  product_scheme_id, transaction_type, order_status, amount, " +
            "  units, payment_mode, mandate_mode, product_category, is_deleted" +
            ") VALUES (" +
            "  '8b3f4a1d-7e2c-49b8-86a3-1f5d9c2e8047', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'COMPLETED', " +
            "  5000.00, 50.0000, 'NET_BANKING', 'E-Mandate', 'MF', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );

        // Active SIF SIP
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, " +
            "  product_scheme_id, transaction_type, order_status, amount, " +
            "  units, payment_mode, mandate_mode, product_category, is_deleted" +
            ") VALUES (" +
            "  '2a7c9e4f-6b1d-4f3a-95e8-3d7b1c4a9e26', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'COMPLETED', " +
            "  25000.00, 250.0000, 'NACH', 'Physical Mandate', 'SIF', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );

        // Failed SIP
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, " +
            "  product_scheme_id, transaction_type, order_status, amount, " +
            "  units, payment_mode, mandate_mode, product_category, is_deleted" +
            ") VALUES (" +
            "  'c1f9b3e8-5a4d-4e7b-83c2-9d6e1f8a3b45', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'FAILED', " +
            "  10000.00, 0, 'NET_BANKING', 'E-Mandate', 'MF', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );

        // Paused / draft SIP
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, " +
            "  product_scheme_id, transaction_type, order_status, amount, " +
            "  units, payment_mode, mandate_mode, product_category, is_deleted" +
            ") VALUES (" +
            "  'f6e2d1c9-8b3a-4f5d-91c4-7e2b8a5d3e91', now(), now(), " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'DRAFT', " +
            "  15000.00, 0, 'NET_BANKING', 'E-Mandate', 'SIF', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 273 — pending redemption against the lumpsum order.
    // ── MVP-B5: redemption UUID swapped, and its order_id reference is
    // updated to point at the new lumpsum UUID above.
    private void seedBobRedemption() {
        jdbc.update(
            "INSERT INTO redemption_records (" +
            "  id, created_at, updated_at, order_id, investor_id, " +
            "  redemption_status, units, amount" +
            ") VALUES (" +
            "  '4b8e9c2f-1d6a-4738-9e2f-5c1d8b3a7e62', now(), now(), " +
            "  '5d8a7c2e-3f9b-4861-94c7-2f6e8d1b3a05', " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'PENDING', " +
            "  50.0000, 5000.00" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 276 — order-completed notification for Bob's lumpsum.
    private void seedBobNotification() {
        jdbc.update(
            "INSERT INTO notifications (" +
            "  id, created_at, updated_at, distributor_id, investor_id, " +
            "  type, title, message, read_flag" +
            ") VALUES (" +
            "  '8fa1ae9b-0000-4b2a-8cfa-5b9d12341234', now(), now(), " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'ORDER_STATUS', " +
            "  'Order Completed', 'Your purchase order is successful.', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 279 — Charlie prospect lead.
    // is_deleted (V15) included explicitly as false.
    private void seedCharlieLead() {
        jdbc.update(
            "INSERT INTO investor_leads (" +
            "  id, created_at, updated_at, prospect_name, mobile_number, " +
            "  email, city, source, status, assigned_distributor_id, " +
            "  is_deleted" +
            ") VALUES (" +
            "  'ca123eb4-0000-4123-85af-bbbbccccdddd', now(), now(), " +
            "  'Charlie Prospect', '9999999999', 'charlie@example.com', " +
            "  'Delhi', 'WEBSITE', 'NEW', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', false" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 282 — Charlie call interaction.
    private void seedCharlieInteraction() {
        jdbc.update(
            "INSERT INTO lead_interactions (" +
            "  id, created_at, updated_at, lead_id, distributor_id, " +
            "  comment_text, interaction_type" +
            ") VALUES (" +
            "  '11112222-3333-4444-5555-666677778888', now(), now(), " +
            "  'ca123eb4-0000-4123-85af-bbbbccccdddd', " +
            "  'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'Called customer but no answer.', 'CALL'" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V1 line 285 — audit event for Alice's signup.
    private void seedAliceSignupAudit() {
        jdbc.update(
            "INSERT INTO audit_events (" +
            "  id, created_at, updated_at, entity_type, entity_id, " +
            "  action_type, actor_id, details_json" +
            ") VALUES (" +
            "  'aaaaabbb-cccc-dddd-eeee-ffff00001111', now(), now(), " +
            "  'DISTRIBUTOR', 'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  'CREATED', 'd9b2d63d-a233-4123-8478-3b169d988b48', " +
            "  '{\"reason\": \"signup\"}'" +
            ") ON CONFLICT (id) DO NOTHING"
        );
    }

    // V23 — local test distributor (a@a.com / Ok@123456).
    // Mirrors V23's ON CONFLICT (email) DO UPDATE so we re-establish the
    // password hash + APPROVED status on every local boot. This is what
    // keeps the demo login working even if someone mutates the row.
    private void upsertTestDistributor() {
        jdbc.update(
            "INSERT INTO distributors (" +
            "  id, created_at, updated_at, full_name, mobile_number, email, " +
            "  arn_number, arn_expiry_date, nism_certificate_number, " +
            "  nism_expiry_date, e_uin_number, status, kyc_status, " +
            "  bank_account_number, bank_ifsc, bank_account_holder_name, " +
            "  profile_completion_percent, internal_rm, role, password_hash" +
            ") VALUES (" +
            "  '4317cfd2-a41f-4320-a5dc-26835c7210ac', now(), now(), " +
            "  'Test Distributor', '9000000001', 'a@a.com', 'ARN-TEST-001', " +
            "  '2028-12-31', 'NISM-TEST-001', '2028-12-31', 'E-UIN-TEST-001', " +
            "  'APPROVED', 'COMPLETED', '100020003001', 'HDFC0000001', " +
            "  'Test Distributor', 100, false, 'MASTER_DISTRIBUTOR', " +
            "  '$2a$10$XQHHUBoV886OteiR8ZpmE.QQL8PCxVvAqPlnivkHSQ1LmuXZFhN5G'" +
            ") ON CONFLICT (email) DO UPDATE SET " +
            "  updated_at = now(), " +
            "  status = 'APPROVED', " +
            "  kyc_status = 'COMPLETED', " +
            "  role = 'MASTER_DISTRIBUTOR', " +
            "  password_hash = EXCLUDED.password_hash"
        );
    }
}

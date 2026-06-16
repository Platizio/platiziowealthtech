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

    private static final String TEST_DISTRIBUTOR_ID = "4317cfd2-a41f-4320-a5dc-26835c7210ac";
    private static final String SCHEME_MF_GROWTH = "a123fef2-7440-42f0-9ef2-5b9db054a123";
    private static final String SCHEME_MF_BALANCED = "a222fef2-7440-42f0-9ef2-5b9db054a222";
    private static final String SCHEME_SIF = "a333fef2-7440-42f0-9ef2-5b9db054a333";

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
        seedShowcaseInvestors();
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
            "  '9988776655', 'bob@example.com', 'GYAPS3751D', '1990-05-15', " +
            "  '123 Main St', 'Mumbai', 'Maharashtra', '400001', 'ACTIVE', " +
            "  'COMPLETED', 'VERIFIED', 'MODERATE', NULL, " +
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

    /**
     * Presentation personas for {@code a@a.com} (Test Distributor) and extra variety
     * on Alice's book. Idempotent — safe on every local boot.
     *
     * <ul>
     *   <li>Priya Sharma — KYC done, bank not added yet</li>
     *   <li>Rahul Mehta — KYC done, bank verification in progress</li>
     *   <li>Anita Verma — fully verified, orders + notifications</li>
     *   <li>Vikram Singh — KYC retry required</li>
     *   <li>Neha Joshi — KYC still in progress</li>
     * </ul>
     */
    private void seedShowcaseInvestors() {
        jdbc.update(
            "UPDATE investors SET external_kyc_check_id = NULL, external_kyc_status = NULL, external_kyc_payload_json = NULL "
                    + "WHERE external_kyc_check_id LIKE 'pv_demo_%'"
        );
        jdbc.update(
            "UPDATE investor_bank_accounts SET cybrilla_bank_verification_id = NULL, cybrilla_bank_verification_status = NULL "
                    + "WHERE cybrilla_bank_verification_id LIKE 'pv_demo_%'"
        );
        jdbc.update(
            "UPDATE investors SET cybrilla_investor_id = NULL "
                    + "WHERE cybrilla_investor_id LIKE 'invp_demo_%' OR cybrilla_investor_id LIKE 'CYB-INV%'"
        );
        jdbc.update(
            "UPDATE investors SET external_mf_investment_account_id = NULL "
                    + "WHERE external_mf_investment_account_id LIKE 'mfia_demo_%'"
        );
        jdbc.update(
            "UPDATE investor_bank_accounts SET cybrilla_bank_id = NULL "
                    + "WHERE cybrilla_bank_id LIKE 'ba_demo_%'"
        );
        seedPriyaKycDoneBankPending();
        seedRahulBankVerifying();
        seedAnitaFullyVerifiedWithActivity();
        repairAnitaStaleFpProfileIfNeeded();
        seedVikramKycRetry();       
        seedNehaKycInProgress();
    }

    private void seedPriyaKycDoneBankPending() {
        String id = "7f3a2b1c-9e8d-4a7b-8c6d-5e4f3a2b1c01";
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, " +
            "  date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, cybrilla_investor_id, " +
            "  external_kyc_status, kyc_readiness_status, kyc_compliance_status, kyc_compliance_reason, " +
            "  kyc_compliance_action, is_deleted, relationship_type, household_id" +
            ") VALUES (" +
            "  ?::uuid, now(), now(), ?::uuid, 'Priya Sharma', '9811100001', 'priya.demo@platizio.local', 'AAAPA3751A', " +
            "  '1992-03-15', '14 Park Street', 'Kolkata', 'West Bengal', '700016', 'ONBOARDING', 'COMPLETED', " +
            "  'NOT_CAPTURED', 'MODERATE', NULL, 'completed', 'verified', " +
            "  true, 'verified', 'allow', false, 'SELF', ?::uuid" +
            ") ON CONFLICT (id) DO NOTHING",
            id, TEST_DISTRIBUTOR_ID, id
        );
        jdbc.update(
            "INSERT INTO notifications (id, created_at, updated_at, distributor_id, investor_id, type, title, message, read_flag) " +
            "VALUES ('a1010001-0001-4001-8001-000000000101', now(), now(), ?::uuid, ?::uuid, 'KYC_COMPLETED', " +
            "'KYC Verified', 'Priya Sharma KYC is complete. Add bank details to continue onboarding.', false) " +
            "ON CONFLICT (id) DO NOTHING",
            TEST_DISTRIBUTOR_ID, id
        );
    }

    private void seedRahulBankVerifying() {
        String id = "8a4b3c2d-1e0f-4b9a-8c7d-6e5f4a3b2c02";
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, " +
            "  date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, cybrilla_investor_id, " +
            "  external_kyc_status, kyc_readiness_status, kyc_compliance_status, is_deleted, " +
            "  relationship_type, household_id" +
            ") VALUES (" +
            "  ?::uuid, now(), now(), ?::uuid, 'Rahul Mehta', '9811100002', 'rahul.demo@platizio.local', 'FFFPF3751F', " +
            "  '1988-07-22', '88 MG Road', 'Bengaluru', 'Karnataka', '560001', 'ONBOARDING', 'COMPLETED', " +
            "  'VERIFICATION_PENDING', 'MODERATE', NULL, 'completed', 'verified', " +
            "  true, false, 'SELF', ?::uuid" +
            ") ON CONFLICT (id) DO UPDATE SET pan = EXCLUDED.pan, updated_at = now()",
            id, TEST_DISTRIBUTOR_ID, id
        );
        jdbc.update(
            "INSERT INTO investor_bank_accounts (" +
            "  id, created_at, updated_at, investor_id, account_holder_name, account_number, ifsc_code, " +
            "  bank_name, branch_name, verification_status" +
            ") VALUES (" +
            "  'b2020002-0002-4002-8002-000000000202', now(), now(), ?::uuid, 'Rahul Mehta', " +
            "  '98123459193', 'HDFC0001330', 'HDFC Bank', 'MG Road', 'VERIFICATION_PENDING'" +
            ") ON CONFLICT (id) DO NOTHING",
            id
        );
        jdbc.update(
            "INSERT INTO notifications (id, created_at, updated_at, distributor_id, investor_id, type, title, message, read_flag) " +
            "VALUES ('a1010002-0002-4002-8002-000000000102', now(), now(), ?::uuid, ?::uuid, 'GENERAL', " +
            "'Bank Verification Pending', 'Rahul Mehta bank account is under penny-drop verification.', false) " +
            "ON CONFLICT (id) DO NOTHING",
            TEST_DISTRIBUTOR_ID, id
        );
    }

    private void seedAnitaFullyVerifiedWithActivity() {
        String id = "9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03";
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, " +
            "  date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, cybrilla_investor_id, onboarding_notes, " +
            "  external_mf_investment_account_id, external_kyc_status, kyc_readiness_status, " +
            "  kyc_compliance_status, kyc_compliance_reason, is_deleted, relationship_type, household_id" +
            ") VALUES (" +
            "  ?::uuid, now(), now(), ?::uuid, 'Anita Verma', '9811100003', 'anita.demo@platizio.in', 'KRTPX3751K', " +
            "  '1985-11-08', '22 Lake View', 'Pune', 'Maharashtra', '411001', 'READY_FOR_TRANSACTIONS', 'COMPLETED', " +
            "  'VERIFIED', 'AGGRESSIVE', NULL, 'gender=female;occupation=service;income=upto_1lakh', NULL, 'completed', " +
            "  'verified', true, 'verified', false, 'SELF', ?::uuid" +
            ") ON CONFLICT (id) DO UPDATE SET " +
            "  updated_at = now(), email = EXCLUDED.email, pan = EXCLUDED.pan, " +
            "  onboarding_notes = EXCLUDED.onboarding_notes, kyc_status = EXCLUDED.kyc_status, " +
            "  bank_verification_status = EXCLUDED.bank_verification_status, investor_status = EXCLUDED.investor_status",
            id, TEST_DISTRIBUTOR_ID, id
        );
        jdbc.update(
            "INSERT INTO investor_bank_accounts (" +
            "  id, created_at, updated_at, investor_id, account_holder_name, account_number, ifsc_code, " +
            "  bank_name, branch_name, verification_status, cybrilla_bank_id" +
            ") VALUES (" +
            "  'b2020003-0003-4003-8003-000000000203', now(), now(), ?::uuid, 'Anita Verma', " +
            "  '98123451193', 'HDFC0001330', 'HDFC Bank', 'FC Road', 'VERIFIED', NULL" +
            ") ON CONFLICT (id) DO NOTHING",
            id
        );

        String lumpsumId = "c3030003-0003-4003-8003-000000000301";
        String sipActiveId = "c3030003-0003-4003-8003-000000000302";
        String sipPaymentPendingId = "c3030003-0003-4003-8003-000000000303";
        String redemptionOrderId = "c3030003-0003-4003-8003-000000000304";

        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, " +
            "  order_status, amount, units, payment_mode, product_category, external_order_id, is_deleted" +
            ") VALUES (" +
            "  ?::uuid, now() - interval '12 days', now() - interval '10 days', ?::uuid, ?::uuid, ?::uuid, 'LUMPSUM_PURCHASE', " +
            "  'COMPLETED', 50000.00, 412.5000, 'NET_BANKING', 'MF', 'fp_purchase_demo_001', false" +
            ") ON CONFLICT (id) DO NOTHING",
            lumpsumId, id, TEST_DISTRIBUTOR_ID, SCHEME_MF_GROWTH
        );
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, " +
            "  order_status, amount, units, payment_mode, mandate_mode, product_category, sip_frequency, " +
            "  sip_start_date, sip_instalments, external_order_id, is_deleted" +
            ") VALUES (" +
            "  ?::uuid, now() - interval '90 days', now() - interval '1 day', ?::uuid, ?::uuid, ?::uuid, 'SIP', 'SUCCESSFUL', " +
            "  5000.00, 48.2500, 'NET_BANKING', 'E-Mandate', 'MF', 'MONTHLY', '2025-09-01', 12, " +
            "  'fp_sip_demo_001', false" +
            ") ON CONFLICT (id) DO UPDATE SET " +
            "  is_deleted = false, deleted_at = NULL, order_status = 'SUCCESSFUL', updated_at = now()",
            sipActiveId, id, TEST_DISTRIBUTOR_ID, SCHEME_MF_BALANCED
        );
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, " +
            "  order_status, amount, payment_mode, mandate_mode, product_category, investor_action_url, is_deleted" +
            ") VALUES (" +
            "  ?::uuid, now() - interval '2 hours', now() - interval '1 hour', ?::uuid, ?::uuid, ?::uuid, 'SIP', 'PAYMENT_PENDING', " +
            "  3000.00, 'NET_BANKING', 'E-Mandate', 'SIF', 'http://localhost:3000/investor-actions/demo-anita-sip', false" +
            ") ON CONFLICT (id) DO NOTHING",
            sipPaymentPendingId, id, TEST_DISTRIBUTOR_ID, SCHEME_SIF
        );
        jdbc.update(
            "INSERT INTO transaction_orders (" +
            "  id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, " +
            "  order_status, amount, units, payment_mode, product_category, is_deleted" +
            ") VALUES (" +
            "  ?::uuid, now() - interval '3 days', now() - interval '2 days', ?::uuid, ?::uuid, ?::uuid, 'REDEMPTION', 'PROCESSING', " +
            "  12000.00, 95.0000, 'NET_BANKING', 'MF', false" +
            ") ON CONFLICT (id) DO NOTHING",
            redemptionOrderId, id, TEST_DISTRIBUTOR_ID, SCHEME_MF_GROWTH
        );
        jdbc.update(
            "INSERT INTO redemption_records (" +
            "  id, created_at, updated_at, order_id, investor_id, redemption_status, units, amount, " +
            "  external_redemption_id" +
            ") VALUES (" +
            "  'd4040003-0003-4003-8003-000000000403', now() - interval '3 days', now() - interval '1 day', " +
            "  ?::uuid, ?::uuid, 'PROCESSING', 95.0000, 12000.00, 'fp_redemption_demo_001'" +
            ") ON CONFLICT (id) DO NOTHING",
            redemptionOrderId, id
        );

        insertNotification("a1010003-0003-4003-8003-000000000103", TEST_DISTRIBUTOR_ID, id,
                "KYC_COMPLETED", "KYC Verified", "Anita Verma is KYC compliant and ready to invest.", true);
        insertNotification("a1010004-0004-4004-8004-000000000104", TEST_DISTRIBUTOR_ID, id,
                "TRANSACTION_SUCCESSFUL", "Lumpsum Successful",
                "₹50,000 invested in Super Growth Fund.", false);
        insertNotification("a1010005-0005-4005-8005-000000000105", TEST_DISTRIBUTOR_ID, id,
                "PAYMENT_PENDING", "SIP Payment Awaiting",
                "₹3,000 monthly SIP in Social Venture Fund needs investor payment confirmation.", false);
        insertNotification("a1010006-0006-4006-8006-000000000106", TEST_DISTRIBUTOR_ID, id,
                "REDEMPTION_SUBMITTED", "Redemption Submitted",
                "Redemption of 95 units (₹12,000) is being processed.", false);
        insertNotification("a1010007-0007-4007-8007-000000000107", TEST_DISTRIBUTOR_ID, id,
                "RECURRING_PLAN_EVENT", "SIP Instalment Debited",
                "₹5,000 SIP instalment for Balanced Advantage Fund was successful.", true);
    }

    /**
     * One-time local repair: Anita's sandbox FP profiles were linked before occupation could
     * be set on POST and/or used a {@code .local} email rejected by ONDC review. Reset stale
     * Finprim linkage only when the demo investor still carries a retired PAN or known-bad FP id.
     */
    private void repairAnitaStaleFpProfileIfNeeded() {
        String id = "9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03";
        jdbc.update(
            "UPDATE investors SET "
                    + "  email = 'anita.demo@platizio.in', "
                    + "  pan = 'KRTPX3751K', "
                    + "  onboarding_notes = 'gender=female;occupation=service;income=upto_1lakh', "
                    + "  cybrilla_investor_id = NULL, "
                    + "  external_mf_investment_account_id = NULL, "
                    + "  external_kyc_check_id = NULL, "
                    + "  external_sync_pending = false, "
                    + "  external_sync_message = NULL, "
                    + "  updated_at = now() "
                    + "WHERE id = ?::uuid "
                    + "  AND (pan <> 'KRTPX3751K' OR email LIKE '%@platizio.local' "
                    + "       OR pan IN ('CCCPC3751C', 'ANVPA3751A', 'EEEPX3751E', 'AVRMPX3751V', 'AVRPX3751A', 'BBBPB3751B') "
                    + "       OR cybrilla_investor_id IN ('invp_3605d8a4b9b049ccb3c623f5e23ddb59', "
                    + "           'invp_7ffe137cad8e4a8887844fe058149878', "
                    + "           'invp_fca5fd4512c847bb8422f23dee938c0a', "
                    + "           'invp_b184067cf9e34905a9748c857674a449'))",
            id
        );
        jdbc.update(
            "UPDATE investor_bank_accounts SET "
                    + "  verification_status = 'VERIFIED', "
                    + "  cybrilla_bank_id = NULL, "
                    + "  fp_bank_account_old_id = NULL, "
                    + "  cybrilla_bank_verification_id = NULL, "
                    + "  cybrilla_bank_verification_status = NULL, "
                    + "  cybrilla_bank_verification_confidence = NULL, "
                    + "  external_sync_pending = false, "
                    + "  external_sync_message = NULL, "
                    + "  updated_at = now() "
                    + "WHERE investor_id = ?::uuid "
                    + "  AND (cybrilla_bank_id IS NOT NULL OR cybrilla_bank_verification_id IS NOT NULL) "
                    + "  AND EXISTS (SELECT 1 FROM investors i WHERE i.id = ?::uuid AND i.cybrilla_investor_id IS NULL)",
            id, id
        );
        jdbc.update(
            "UPDATE investor_bank_accounts SET account_number = '98123451193', verification_status = 'VERIFIED', updated_at = now() "
                    + "WHERE investor_id = ?::uuid AND account_number = '98123459193'",
            id
        );
    }

    private void seedVikramKycRetry() {
        String id = "1c6d5e4f-3a2b-4d1c-0e9f-8a7b6c5d4e04";
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, " +
            "  date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, " +
            "  kyc_readiness_status, kyc_readiness_reason, is_deleted, relationship_type, household_id" +
            ") VALUES (" +
            "  ?::uuid, now(), now(), ?::uuid, 'Vikram Singh', '9811100004', 'vikram.demo@platizio.local', 'GYAPS3753D', " +
            "  '1994-01-30', '9 Civil Lines', 'Jaipur', 'Rajasthan', '302006', 'ONBOARDING', 'NOT_STARTED', " +
            "  'NOT_CAPTURED', 'UNASSESSED', 'failed', " +
            "  'Fresh KYC required — no reusable KYC record in sandbox.', false, 'SELF', ?::uuid" +
            ") ON CONFLICT (id) DO NOTHING",
            id, TEST_DISTRIBUTOR_ID, id
        );
        insertNotification("a1010008-0008-4008-8008-000000000108", TEST_DISTRIBUTOR_ID, id,
                "KYC_FAILED", "KYC Retry Required",
                "Vikram Singh KYC failed: name does not match PAN records.", false);
    }

    private void seedNehaKycInProgress() {
        String id = "2d7e6f5a-4b3c-4e2d-1f0a-9b8c7d6e5f05";
        jdbc.update(
            "INSERT INTO investors (" +
            "  id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, " +
            "  date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, " +
            "  bank_verification_status, risk_profile, external_kyc_status, " +
            "  kyc_readiness_status, is_deleted, relationship_type, household_id" +
            ") VALUES (" +
            "  ?::uuid, now(), now(), ?::uuid, 'Neha Joshi', '9811100005', 'neha.demo@platizio.local', 'CCCPC3753C', " +
            "  '1996-09-12', '31 Ring Road', 'Ahmedabad', 'Gujarat', '380015', 'ONBOARDING', 'IN_PROGRESS', " +
            "  'NOT_CAPTURED', 'CONSERVATIVE', 'pending', 'pending', false, 'SELF', ?::uuid" +
            ") ON CONFLICT (id) DO UPDATE SET pan = EXCLUDED.pan, updated_at = now()",
            id, TEST_DISTRIBUTOR_ID, id
        );
        insertNotification("a1010009-0009-4009-8009-000000000109", TEST_DISTRIBUTOR_ID, id,
                "GENERAL", "KYC In Progress",
                "Neha Joshi KYC pre-verification is running with Cybrilla.", false);
    }

    private void insertNotification(
            String notificationId,
            String distributorId,
            String investorId,
            String type,
            String title,
            String message,
            boolean read
    ) {
        jdbc.update(
            "INSERT INTO notifications (id, created_at, updated_at, distributor_id, investor_id, type, title, message, read_flag) " +
            "VALUES (?::uuid, now(), now(), ?::uuid, ?::uuid, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            notificationId, distributorId, investorId, type, title, message, read
        );
    }
}

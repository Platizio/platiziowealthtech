package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;
import java.util.Map;

public interface CybrillaClient {

    /**
     * B-68: the result of a paginated scheme fetch. `complete=true` means the
     * client traversed every page successfully and the `schemes` list is the
     * canonical current catalogue; `complete=false` means the fetch terminated
     * before consuming the full catalogue (e.g. a per-call page cap was hit).
     *
     * Callers that maintain "active set vs catalogue" state — like
     * ProductService.refreshFromCybrilla — MUST skip deactivation of
     * locally-known schemes whenever `complete=false`, otherwise a partial
     * fetch can incorrectly mark legitimate schemes inactive.
     *
     * The `incompleteReason` is a short machine-readable tag (e.g.
     * "max_pages_reached") suitable for structured logging.
     */
    record SchemeFetchResult(List<ProductScheme> schemes, boolean complete, String incompleteReason) {
        public static SchemeFetchResult complete(List<ProductScheme> schemes) {
            return new SchemeFetchResult(schemes, true, null);
        }
        public static SchemeFetchResult partial(List<ProductScheme> schemes, String reason) {
            return new SchemeFetchResult(schemes, false, reason);
        }
    }

    /** One Finprim catalogue page mapped for UI use plus the raw provider JSON. */
    record LiveCataloguePage(
            JsonNode rawResponse,
            List<ProductScheme> schemes,
            long totalElements,
            int page,
            int size,
            String finprimPath
    ) {
    }

    String createInvestorProfile(Investor investor);
    void updateInvestorProfile(Investor investor);
    /** Patches FATCA/order-required profile fields without resending immutable identity attributes. */
    void ensureInvestorProfileOrderReady(Investor investor);
    /** FP tenant: GET /v2/investor_profiles (requires {@code pan} or {@code type}). */
    JsonNode listInvestorProfiles(String pan, String type);
    /** FP tenant: GET /v2/investor_profiles/:id */
    JsonNode fetchInvestorProfile(String profileId);
    /** Creates FP address, email, and phone child resources when local investor data is present. */
    void syncInvestorContactResources(Investor investor);
    /** FP tenant: GET /v2/mf_investment_accounts?primary_investor={invp_id} */
    JsonNode listMfInvestmentAccounts(String investorProfileId);
    String createMfInvestmentAccount(Investor investor);
    /**
     * Ensures FP {@code folio_defaults} are set on the investor's MF investment account
     * (required before POA orders when the account is linked to an investor profile).
     */
    void ensureMfInvestmentAccountOrderReady(Investor investor, InvestorBankAccount bankAccount);
    void captureBankAccount(Investor investor, InvestorBankAccount bankAccount);
    /** Creates FP {@code bac_} on the investor profile without starting POA/FP bank verification. */
    void ensureFpBankAccountCaptured(Investor investor, InvestorBankAccount bankAccount);
    void startBankAccountVerification(Investor investor, InvestorBankAccount bankAccount);
    /** FP onboarding lookup: GET /api/onb/ifsc_codes/{ifsc_code} */
    IfscLookupResult fetchIfscDetails(String ifscCode);
    /** FP onboarding lookup: GET /api/onb/pincodes/{pincode} */
    PincodeLookupResult fetchPincodeDetails(String pincode);
    SchemeFetchResult fetchProductSchemes();
    String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme);
    JsonNode fetchBankAccountVerification(String bankAccountVerificationId);
    JsonNode fetchBankAccountVerificationWithPayloadSnapshot(String bankAccountVerificationId, Map<String, Object> payloadSnapshot);
    JsonNode createPreVerification(Map<String, Object> payload);
    /** POA PAN/name/DOB validation shape for onboarding step 3. */
    JsonNode createKycCheck(Investor investor);
    /** POA readiness + PAN + bank in one pre_verification for ONDC purchase review. */
    JsonNode createCombinedOrderPreVerification(Investor investor, InvestorBankAccount bankAccount);
    /** POA readiness-only shape ({@code investor_identifier}) for post-submit apply flow. */
    JsonNode createReadinessCheck(Investor investor);
    JsonNode fetchKycCheck(String kycCheckId);
    JsonNode refetchKycCheck(String kycCheckId);

    // FP KYC Check (KRA compliance status). FP tenant surface:
    // POST /api/kyc/check, GET /api/kyc/{id}, PUT /api/kyc/{id}/refetch.
    // Pass dateOfBirth only to also fetch demographic entity_details (RIA/AMC licence).
    JsonNode createKycComplianceCheck(String pan, java.time.LocalDate dateOfBirth);
    JsonNode fetchKycComplianceCheck(String kycComplianceCheckId);
    JsonNode refetchKycComplianceCheck(String kycComplianceCheckId);
    JsonNode listKycRequests(String pan, String status);
    JsonNode createKycRequest(Map<String, Object> payload);
    JsonNode fetchKycRequest(String kycRequestId);
    JsonNode updateKycRequest(String kycRequestId, Map<String, Object> payload);
    JsonNode simulateKycRequest(String kycRequestId, String status);
    JsonNode createIdentityDocument(Map<String, Object> payload);
    JsonNode fetchIdentityDocument(String identityDocumentId);
    JsonNode listIdentityDocuments(String kycRequestId, String fetchStatus);
    JsonNode createEsign(Map<String, Object> payload);
    JsonNode fetchEsign(String esignId);

    // Cybrilla POA KYC Forms API (modify workflow). POST/PATCH /poa/kyc_forms,
    // GET /poa/kyc_forms/{id}, plus signature upload and proof-fetch retry.
    JsonNode createKycForm(Map<String, Object> payload);
    JsonNode updateKycForm(String kycFormId, Map<String, Object> payload);
    JsonNode fetchKycForm(String kycFormId);
    JsonNode uploadKycFormSignature(String kycFormId, byte[] fileBytes, String filename, String contentType);
    JsonNode retryKycFormProofDetailsFetch(String kycFormId);
    String generateInvestorActionUrl(TransactionOrder order);
    JsonNode fetchMfPurchase(String mfPurchaseId);
    JsonNode updateMfPurchaseConsent(String mfPurchaseId, Map<String, Object> consent);
    JsonNode createNetbankingPayment(List<Integer> amcOrderIds, String paymentPostbackUrl, String paymentMethod);
    JsonNode createNetbankingPayment(
            List<Integer> amcOrderIds,
            String paymentPostbackUrl,
            String paymentMethod,
            Integer bankAccountOldId,
            String providerName
    );
    JsonNode fetchPayment(int paymentId);
    JsonNode simulatePayment(int paymentId, String status);
    JsonNode confirmMfPurchase(String mfPurchaseId);
    JsonNode fetchBankAccount(String bankAccountId);
    JsonNode createMandate(int bankAccountOldId, String mandateType, int mandateLimit, String providerName);
    JsonNode authorizeMandate(int mandateId, String paymentPostbackUrl);
    JsonNode fetchMandate(int mandateId);
    JsonNode simulateMandate(int mandateId, String status);
    JsonNode fetchMfPurchasePlan(String mfPurchasePlanId);
    JsonNode updateMfPurchasePlan(String mfPurchasePlanId, Map<String, Object> payload);
    JsonNode listMfPurchasesForPlan(String mfPurchasePlanId);
    JsonNode createNachPayment(int mandateId, List<Integer> amcOrderIds);
    String createSipOrderWithMandate(TransactionOrder order, Investor investor, ProductScheme productScheme, int mandateId);
    String createRedemption(TransactionOrder order, Investor investor, ProductScheme productScheme);
    void cancelOrder(TransactionOrder order);

    /**
     * Cancels an FP mf_purchase_plan via {@code POST /v2/mf_purchase_plans/cancel}.
     * Returns the provider response (state should be {@code cancelled}).
     */
    com.fasterxml.jackson.databind.JsonNode cancelPurchasePlan(String planId, String cancellationCode, String cancellationReason);
    JsonNode getFundSchemesPageWithPayloadSnapshot(int page, int size, Map<String, Object> payloadSnapshot);

    /**
     * Live catalogue page from Finprim. {@code endpoint} is one of:
     * {@code poa-mf} ({@code GET /v2/mf_scheme_plans/cybrillapoa}),
     * {@code oms-fund-schemes} ({@code GET /api/oms/fund_schemes}),
     * {@code sif-poa} ({@code GET /v2/sif_scheme_plans/cybrillapoa}).
     */
    LiveCataloguePage fetchLiveCataloguePage(String endpoint, int page, int size);
}

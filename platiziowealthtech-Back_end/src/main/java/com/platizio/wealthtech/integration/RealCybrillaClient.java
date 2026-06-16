package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.ExternalApiAuthenticationException;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import com.platizio.wealthtech.service.ExternalApiSnapshotService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "cybrilla.integration", name = "real-client-enabled", havingValue = "true", matchIfMissing = true)
public class RealCybrillaClient implements CybrillaClient {

    private static final Logger logger = LoggerFactory.getLogger(RealCybrillaClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TENANT_HEADER = "x-tenant-id";
    private static final int FUND_SCHEME_PAGE_SIZE = 100;
    private static final int FUND_SCHEME_MAX_PAGES = 50;
    private static final int FUND_SCHEME_RATE_LIMIT_RETRIES = 3;
    private static final long FUND_SCHEME_RATE_LIMIT_BACKOFF_MILLIS = 5_000L;
    // Transient connectivity (DNS/connect/timeout) retry — small, fast backoff.
    private static final int CONNECTIVITY_RETRY_MAX_ATTEMPTS = 3;
    /** One Finprim bearer per catalogue batch — avoids dozens of cache lookups per paginated import. */
    private static final ThreadLocal<String> SCOPED_TENANT_BEARER = new ThreadLocal<>();
    /** After 404, tenant has no SIF catalogue — do not call the SIF endpoint again this JVM lifetime. */
    private volatile boolean sifCatalogueUnavailable;
    private static final long CONNECTIVITY_RETRY_BACKOFF_MILLIS = 500L;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String MF_PURCHASES_PATH = "/v2/mf_purchases";
    private static final String MF_PURCHASE_PLANS_PATH = "/v2/mf_purchase_plans";
    private static final String MF_REDEMPTIONS_PATH = "/v2/mf_redemptions";
    private static final String IFSC_LOOKUP_PATH = "/api/onb/ifsc_codes/";
    private static final String PINCODE_LOOKUP_PATH = "/api/onb/pincodes/";
    private static final String NETBANKING_PAYMENT_PATH = "/api/pg/payments/netbanking";
    private static final String PAYMENTS_PATH = "/api/pg/payments";
    private static final String NACH_PAYMENT_PATH = "/api/pg/payments/nach";
    private static final String MANDATES_PATH = "/api/pg/mandates";
    private static final String EMANDATE_AUTH_PATH = "/api/pg/payments/emandate/auth";
    private static final String SIMULATE_MANDATES_PATH = "/api/pg/simulate/mandates";
    private static final String SIMULATE_PAYMENTS_PATH = "/api/pg/simulate/payments";
    private static final String CYBRILLAPOA_PAYMENT_PROVIDER = "ONDC";
    private static final String BANK_ACCOUNTS_PATH = "/v2/bank_accounts";
    private static final String BANK_ACCOUNT_VERIFICATIONS_PATH = "/v2/bank_account_verifications";
    /** Gateway id for POA-tradeable scheme plan catalogues (see repo exports under exports/). */
    private static final String POA_ORDER_GATEWAY = "cybrillapoa";
    /** FP cybrillapoa purchases are routed through the {@code ondc} order gateway (see Create MF Purchase API). */
    private static final String MF_PURCHASE_GATEWAY = "ondc";

    private final RestClient restClient;
    private final RestClient poaRestClient;
    private final ExternalBearerTokenService tokenService;
    private final FinprimTenantProperties finprimProperties;
    private final MeterRegistry meterRegistry;
    private final ExternalApiSnapshotService externalApiSnapshotService;
    /**
     * Catalogue endpoint the admin sync seeds from. Default poa-mf routes POA-orderable MF plans;
     * field-injected so existing constructor call sites stay unchanged (null => poa-mf).
     */
    @Value("${cybrilla.integration.product-catalogue-endpoint:poa-mf}")
    private String catalogueEndpoint;

    public RealCybrillaClient(
            RestClient.Builder restClientBuilder,
            ExternalBearerTokenService tokenService,
            FinprimTenantProperties finprimProperties,
            CybrillaPreVerificationProperties poaProperties,
            MeterRegistry meterRegistry,
            ExternalApiSnapshotService externalApiSnapshotService
    ) {
        this.restClient = restClientBuilder
                .baseUrl(finprimProperties.getBaseUrl())
                .build();
        this.poaRestClient = restClientBuilder
                .baseUrl(poaProperties.getBaseUrl())
                .build();
        this.tokenService = tokenService;
        this.finprimProperties = finprimProperties;
        this.meterRegistry = meterRegistry;
        this.externalApiSnapshotService = externalApiSnapshotService;
        logger.info("cybrilla_client mode='real' base_url='{}' tenant_header_configured='{}'", finprimProperties.getBaseUrl(), StringUtils.hasText(finprimProperties.tenantHeaderValue()));
        logger.info("cybrilla_poa_client mode='real' base_url='{}'", poaProperties.getBaseUrl());
    }

    @Override
    public String createInvestorProfile(Investor investor) {
        logger.info("cybrilla_workflow operation='create_investor_profile' status='started' local_investor_id='{}'", investor.getId());
        String existingProfileId = findInvestorProfileIdByPan(investor.getPan());
        if (StringUtils.hasText(existingProfileId)) {
            JsonNode existingProfile = fetchInvestorProfile(existingProfileId);
            assertExistingProfileSupportsOrderSubmission(investor, existingProfileId, existingProfile);
            String fpPan = existingProfile.path("pan").asText("");
            String localPan = investor.getPan() == null ? "" : investor.getPan().trim().toUpperCase(Locale.ROOT);
            if (StringUtils.hasText(fpPan) && StringUtils.hasText(localPan) && !localPan.equalsIgnoreCase(fpPan.trim())) {
                logger.warn(
                        "cybrilla_workflow operation='create_investor_profile' status='pan_mismatch_on_existing' local_investor_id='{}' external_profile_id='{}' local_pan='{}' fp_pan='{}'",
                        investor.getId(),
                        existingProfileId,
                        localPan,
                        fpPan);
            }
            logger.info(
                    "cybrilla_workflow operation='create_investor_profile' status='resolved_existing' local_investor_id='{}' external_profile_id='{}'",
                    investor.getId(),
                    existingProfileId);
            syncInvestorContactResourcesForProfile(existingProfileId, investor);
            investor.setCybrillaInvestorId(existingProfileId);
            ensureInvestorProfileOrderReady(investor);
            return existingProfileId;
        }
        String profileId;
        try {
            profileId = executeWithTenantTokenRetry("create investor profile", () -> {
                JsonNode response = post("create_investor_profile", "/v2/investor_profiles", investorProfilePayload(investor));
                return extractId(response, "investor profile");
            });
        } catch (CybrillaApiException ex) {
            existingProfileId = findInvestorProfileIdByPan(investor.getPan());
            if (StringUtils.hasText(existingProfileId)) {
                JsonNode existingProfile = fetchInvestorProfile(existingProfileId);
                assertExistingProfileSupportsOrderSubmission(investor, existingProfileId, existingProfile);
                logger.info(
                        "cybrilla_workflow operation='create_investor_profile' status='resolved_after_create_conflict' local_investor_id='{}' external_profile_id='{}'",
                        investor.getId(),
                        existingProfileId);
                syncInvestorContactResourcesForProfile(existingProfileId, investor);
                investor.setCybrillaInvestorId(existingProfileId);
                ensureInvestorProfileOrderReady(investor);
                return existingProfileId;
            }
            throw ex;
        }
        logger.info("cybrilla_workflow operation='create_investor_profile' status='completed' local_investor_id='{}' external_profile_id='{}'", investor.getId(), profileId);

        syncInvestorContactResourcesForProfile(profileId, investor);
        return profileId;
    }

    @Override
    public void updateInvestorProfile(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before updating profile details");
        }
        String profileId = investor.getCybrillaInvestorId().trim();
        JsonNode existingProfile = fetchInvestorProfile(profileId);
        Map<String, Object> payload = investorProfileUpdatePayload(investor, existingProfile);
        if (payload.size() <= 1) {
            logger.info(
                    "cybrilla_workflow operation='update_investor_profile' status='no_mutable_changes' local_investor_id='{}' external_profile_id='{}'",
                    investor.getId(),
                    profileId);
            return;
        }
        patchInvestorProfileWithImmutableRetry("update_investor_profile", payload, investor.getId(), profileId);
    }

    @Override
    public void ensureInvestorProfileOrderReady(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before preparing profile for orders");
        }
        String profileId = investor.getCybrillaInvestorId().trim();
        JsonNode existingProfile = fetchInvestorProfile(profileId);
        Map<String, Object> payload = investorProfileOrderReadyPayload(investor, existingProfile);
        if (payload.size() <= 1) {
            logger.info(
                    "cybrilla_workflow operation='ensure_investor_profile_order_ready' status='already_ready' local_investor_id='{}' external_profile_id='{}'",
                    investor.getId(),
                    profileId);
            return;
        }
        patchInvestorProfileWithImmutableRetry(
                "ensure_investor_profile_order_ready",
                payload,
                investor.getId(),
                profileId
        );
    }

    /**
     * PATCH investor profile with immutable-field retry. Kept outside {@link #executeWithTenantTokenRetry}
     * so Spring 6 {@code HttpStatusCode} 400 responses are handled here instead of being wrapped before retry.
     */
    private void patchInvestorProfileWithImmutableRetry(
            String operation,
            Map<String, Object> payload,
            UUID localInvestorId,
            String profileId
    ) {
        try {
            executeWithConnectivityRetry(operation, () -> {
                patchInvestorProfileOrderReady(payload);
                return null;
            });
            logger.info(
                    "cybrilla_workflow operation='{}' status='completed' local_investor_id='{}' external_profile_id='{}'",
                    operation,
                    localInvestorId,
                    profileId
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokenService.invalidateFinprimTenantToken();
                clearScopedTenantBearer();
                executeWithConnectivityRetry(operation, () -> {
                    patchInvestorProfileOrderReady(payload);
                    return null;
                });
                logger.info(
                        "cybrilla_workflow operation='{}' status='completed_after_token_refresh' local_investor_id='{}' external_profile_id='{}'",
                        operation,
                        localInvestorId,
                        profileId
                );
                return;
            }
            throw apiException(operation, ex);
        } catch (RestClientException ex) {
            throw apiException(operation, ex);
        }
    }

    @Override
    public JsonNode listInvestorProfiles(String pan, String type) {
        return executeWithTenantTokenRetry("list investor profiles", () ->
                recordApiRequest("list_investor_profiles", () -> {
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> {
                                var builder = uriBuilder.path("/v2/investor_profiles");
                                if (StringUtils.hasText(pan)) {
                                    builder.queryParam("pan", pan.trim().toUpperCase(Locale.ROOT));
                                } else {
                                    builder.queryParam("type", StringUtils.hasText(type) ? type.trim() : "individual");
                                }
                                return builder.build();
                            })
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", "list_investor_profiles", "GET", "/v2/investor_profiles", null, response);
                    return response;
                }));
    }

    @Override
    public JsonNode fetchInvestorProfile(String profileId) {
        if (!StringUtils.hasText(profileId)) {
            throw new CybrillaApiException("Investor profile id is required");
        }
        String path = "/v2/investor_profiles/" + profileId.trim();
        return executeWithTenantTokenRetry("fetch investor profile", () -> get("fetch_investor_profile", path));
    }

    @Override
    public void syncInvestorContactResources(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before syncing contact resources");
        }
        ensureInvestorContactResources(investor.getCybrillaInvestorId(), investor);
    }

    @Override
    public JsonNode listMfInvestmentAccounts(String investorProfileId) {
        if (!StringUtils.hasText(investorProfileId)) {
            throw new CybrillaApiException("Investor profile id is required to list MF investment accounts");
        }
        String profileId = investorProfileId.trim();
        return executeWithTenantTokenRetry("list mf investment accounts", () ->
                recordApiRequest("list_mf_investment_accounts", () -> {
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v2/mf_investment_accounts")
                                    .queryParam("primary_investor", profileId)
                                    .build())
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "list_mf_investment_accounts",
                            "GET",
                            "/v2/mf_investment_accounts",
                            null,
                            response);
                    return response;
                }));
    }

    @Override
    public String createMfInvestmentAccount(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before opening an MF investment account");
        }

        String existingAccountId = findMfInvestmentAccountId(investor.getCybrillaInvestorId());
        if (StringUtils.hasText(existingAccountId)) {
            logger.info(
                    "cybrilla_workflow operation='create_mf_investment_account' status='resolved_existing' local_investor_id='{}' external_mf_investment_account_id='{}'",
                    investor.getId(),
                    existingAccountId);
            return existingAccountId;
        }

        String investmentAccountId;
        try {
            investmentAccountId = executeWithTenantTokenRetry("create MF investment account", () -> {
                JsonNode response = post("create_mf_investment_account", "/v2/mf_investment_accounts", mfInvestmentAccountPayload(investor));
                return extractId(response, "MF investment account");
            });
        } catch (CybrillaApiException ex) {
            if (isDuplicateMfInvestmentAccountError(ex)) {
                existingAccountId = findMfInvestmentAccountId(investor.getCybrillaInvestorId());
                if (StringUtils.hasText(existingAccountId)) {
                    logger.info(
                            "cybrilla_workflow operation='create_mf_investment_account' status='resolved_after_create_conflict' local_investor_id='{}' external_mf_investment_account_id='{}'",
                            investor.getId(),
                            existingAccountId);
                    return existingAccountId;
                }
            }
            throw ex;
        }
        logger.info("cybrilla_workflow operation='create_mf_investment_account' status='completed' local_investor_id='{}' external_mf_investment_account_id='{}'", investor.getId(), investmentAccountId);
        investor.setExternalMfInvestmentAccountId(investmentAccountId);
        return investmentAccountId;
    }

    @Override
    public void ensureMfInvestmentAccountOrderReady(Investor investor, InvestorBankAccount bankAccount) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())
                || !StringUtils.hasText(investor.getExternalMfInvestmentAccountId())) {
            return;
        }
        ensureInvestorContactResources(investor.getCybrillaInvestorId(), investor);
        String profileId = investor.getCybrillaInvestorId().trim();
        if (bankAccount != null) {
            reconcileFpBankAccountForOrder(investor, bankAccount, profileId);
            ensureFpBankAccountVerificationForOrder(bankAccount);
        }
        String emailId = resolveFolioDefaultEmailId(profileId, investor);
        String phoneId = resolveFolioDefaultPhoneId(profileId, investor);
        String addressId = latestProfileLinkedResourceId("/v2/addresses", profileId);
        String payoutBankId = bankAccount != null && StringUtils.hasText(bankAccount.getCybrillaBankId())
                ? bankAccount.getCybrillaBankId().trim()
                : latestProfileLinkedResourceId("/v2/bank_accounts", profileId);
        if (!StringUtils.hasText(emailId)
                || !StringUtils.hasText(phoneId)
                || !StringUtils.hasText(addressId)
                || !StringUtils.hasText(payoutBankId)) {
            throw new CybrillaApiException(
                    "Fintech Primitives folio_defaults require email, phone, address, and payout bank resources on the investor profile before placing orders");
        }
        logger.info(
                "cybrilla_workflow operation='resolve_folio_defaults' local_investor_id='{}' external_profile_id='{}' email_id='{}' phone_id='{}' address_id='{}' payout_bank_id='{}'",
                investor.getId(),
                profileId,
                emailId,
                phoneId,
                addressId,
                payoutBankId
        );
        Map<String, Object> folioDefaults = new LinkedHashMap<>();
        put(folioDefaults, "communication_email_address", emailId);
        put(folioDefaults, "communication_mobile_number", phoneId);
        put(folioDefaults, "communication_address", addressId);
        put(folioDefaults, "payout_bank_account", payoutBankId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", investor.getExternalMfInvestmentAccountId().trim());
        payload.put("folio_defaults", folioDefaults);
        executeWithTenantTokenRetry("update mf investment account folio defaults", () -> {
            patch("update_mf_investment_account_folio_defaults", "/v2/mf_investment_accounts", payload);
            logger.info(
                    "cybrilla_workflow operation='update_mf_investment_account_folio_defaults' status='completed' local_investor_id='{}' external_mf_investment_account_id='{}'",
                    investor.getId(),
                    investor.getExternalMfInvestmentAccountId());
            return null;
        });
    }

    private void ensureFpBankAccountVerificationForOrder(InvestorBankAccount bankAccount) {
        if (bankAccount == null || !StringUtils.hasText(bankAccount.getCybrillaBankId())) {
            return;
        }
        String accountNumber = normalizeAccountNumber(bankAccount.getAccountNumber());
        if (!accountNumber.endsWith("1193")) {
            return;
        }
        String bankAccountId = bankAccount.getCybrillaBankId().trim();
        JsonNode created;
        try {
            created = executeWithTenantTokenRetry("create FP bank account verification", () ->
                    post("create_fp_bank_account_verification", BANK_ACCOUNT_VERIFICATIONS_PATH, Map.of("bank_account", bankAccountId)));
        } catch (CybrillaApiException ex) {
            if (isNotFound(ex) || isFpBankVerificationUnavailable(ex)) {
                logger.warn(
                        "cybrilla_workflow operation='fp_bank_account_verification' status='skipped_unavailable' local_bank_id='{}' external_bank_id='{}' reason='{}'",
                        bankAccount.getId(),
                        bankAccountId,
                        ex.getMessage());
                return;
            }
            throw ex;
        }
        String verificationId = extractId(created, "FP bank account verification");
        JsonNode settled = pollFpBankAccountVerificationUntilSettled(verificationId);
        String status = firstText(settled, "status");
        String confidence = firstText(settled, "confidence");
        if (!"completed".equalsIgnoreCase(status)) {
            throw new CybrillaApiException(
                    "Fintech Primitives bank account verification did not complete for payout bank "
                            + bankAccountId
                            + (StringUtils.hasText(status) ? ": " + status : ""));
        }
        if (!isEligibleFpBankVerificationConfidence(confidence)) {
            throw new CybrillaApiException(
                    "Fintech Primitives bank account verification confidence is not eligible for orders on "
                            + bankAccountId
                            + (StringUtils.hasText(confidence) ? ": " + confidence : ""));
        }
        logger.info(
                "cybrilla_workflow operation='fp_bank_account_verification' status='verified' local_bank_id='{}' external_bank_id='{}' external_verification_id='{}' confidence='{}'",
                bankAccount.getId(),
                bankAccountId,
                verificationId,
                confidence);
    }

    private JsonNode pollFpBankAccountVerificationUntilSettled(String verificationId) {
        JsonNode verification = null;
        for (int attempt = 1; attempt <= 15; attempt++) {
            verification = fetchBankAccountVerification(verificationId);
            String status = firstText(verification, "status");
            if ("completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                return verification;
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CybrillaUnavailableException(
                        "Interrupted while waiting for Fintech Primitives bank account verification to settle for "
                                + verificationId,
                        ex);
            }
        }
        return verification;
    }

    private static boolean isEligibleFpBankVerificationConfidence(String confidence) {
        if (!StringUtils.hasText(confidence)) {
            return false;
        }
        return switch (confidence.trim().toLowerCase(Locale.ROOT)) {
            case "very_high", "high", "uncertain" -> true;
            default -> false;
        };
    }

    private void reconcileFpBankAccountForOrder(Investor investor, InvestorBankAccount bankAccount, String profileId) {
        String localAccountNumber = normalizeAccountNumber(bankAccount.getAccountNumber());
        String localIfsc = normalizeIfsc(bankAccount.getIfscCode());
        if (!StringUtils.hasText(localAccountNumber)) {
            throw new CybrillaApiException("Local bank account number is required before order placement");
        }
        if (ExternalReferenceIds.isFpBankAccountId(bankAccount.getCybrillaBankId())) {
            if (fpBankAccountMatchesLocal(bankAccount.getCybrillaBankId(), localAccountNumber, localIfsc)) {
                syncFpBankAccountOldId(bankAccount);
                return;
            }
            logger.warn(
                    "cybrilla_workflow operation='reconcile_fp_bank_account' status='stale_local_link' local_investor_id='{}' local_bank_id='{}' external_bank_id='{}' local_account='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankId(),
                    localAccountNumber);
            clearLocalFpBankLinkage(bankAccount);
        }
        String matchedBankId = findProfileLinkedBankAccountId(profileId, localAccountNumber, localIfsc);
        if (StringUtils.hasText(matchedBankId)) {
            bankAccount.setCybrillaBankId(matchedBankId);
            syncFpBankAccountOldId(bankAccount);
            logger.info(
                    "cybrilla_workflow operation='link_existing_bank_account' status='matched_account_number' local_investor_id='{}' local_bank_id='{}' external_bank_id='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    matchedBankId);
            return;
        }
        captureBankAccount(investor, bankAccount);
    }

    private void syncFpBankAccountOldId(InvestorBankAccount bankAccount) {
        if (bankAccount.getFpBankAccountOldId() != null && bankAccount.getFpBankAccountOldId() > 0) {
            return;
        }
        if (!ExternalReferenceIds.isFpBankAccountId(bankAccount.getCybrillaBankId())) {
            return;
        }
        JsonNode bankAccountNode = fetchBankAccount(bankAccount.getCybrillaBankId());
        int oldId = bankAccountNode.path("old_id").asInt(0);
        if (oldId > 0) {
            bankAccount.setFpBankAccountOldId(oldId);
        }
    }

    private static void clearLocalFpBankLinkage(InvestorBankAccount bankAccount) {
        bankAccount.setCybrillaBankId(null);
        bankAccount.setFpBankAccountOldId(null);
        bankAccount.setCybrillaBankVerificationId(null);
        bankAccount.setCybrillaBankVerificationStatus(null);
        bankAccount.setCybrillaBankVerificationConfidence(null);
    }

    private boolean fpBankAccountMatchesLocal(String fpBankAccountId, String localAccountNumber, String localIfsc) {
        if (!ExternalReferenceIds.isFpBankAccountId(fpBankAccountId) || !StringUtils.hasText(localAccountNumber)) {
            return false;
        }
        try {
            JsonNode fpBank = fetchBankAccount(fpBankAccountId.trim());
            String fpAccountNumber = normalizeAccountNumber(firstText(fpBank, "account_number", "accountNumber"));
            if (!localAccountNumber.equals(fpAccountNumber)) {
                return false;
            }
            if (!StringUtils.hasText(localIfsc)) {
                return true;
            }
            String fpIfsc = normalizeIfsc(firstText(fpBank, "ifsc_code", "ifscCode"));
            return !StringUtils.hasText(fpIfsc) || localIfsc.equalsIgnoreCase(fpIfsc);
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='fetch_bank_account' status='failed' external_bank_id='{}' reason='{}'",
                    fpBankAccountId,
                    ex.getMessage());
            return false;
        }
    }

    private String findProfileLinkedBankAccountId(String profileId, String localAccountNumber, String localIfsc) {
        if (!StringUtils.hasText(profileId) || !StringUtils.hasText(localAccountNumber)) {
            return null;
        }
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor bank accounts", () ->
                    recordApiRequest("list_profile_bank_accounts", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path(BANK_ACCOUNTS_PATH)
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_bank_accounts",
                                "GET",
                                BANK_ACCOUNTS_PATH,
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            for (JsonNode item : data) {
                if (!profileId.trim().equalsIgnoreCase(item.path("profile").asText(""))) {
                    continue;
                }
                String accountNumber = normalizeAccountNumber(firstText(item, "account_number", "accountNumber"));
                if (!localAccountNumber.equals(accountNumber)) {
                    continue;
                }
                if (StringUtils.hasText(localIfsc)) {
                    String ifsc = normalizeIfsc(firstText(item, "ifsc_code", "ifscCode"));
                    if (StringUtils.hasText(ifsc) && !localIfsc.equalsIgnoreCase(ifsc)) {
                        continue;
                    }
                }
                return item.path("id").asText(null);
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='list_profile_bank_accounts' status='failed' external_profile_id='{}' reason='{}'",
                    profileId,
                    ex.getMessage());
        }
        return null;
    }

    private static String normalizeAccountNumber(String accountNumber) {
        return accountNumber == null ? "" : accountNumber.trim();
    }

    private static String normalizeIfsc(String ifscCode) {
        return ifscCode == null ? "" : ifscCode.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        ensureFpBankAccountCaptured(investor, bankAccount);
        startBankAccountVerification(investor, bankAccount);
    }

    @Override
    public void ensureFpBankAccountCaptured(Investor investor, InvestorBankAccount bankAccount) {
        if (StringUtils.hasText(bankAccount.getCybrillaBankId())) {
            return;
        }
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before adding a bank account");
        }

        String bankAccountId = executeWithTenantTokenRetry("create bank account", () -> {
            JsonNode response = post("create_bank_account", BANK_ACCOUNTS_PATH, bankAccountPayload(investor, bankAccount));
            bankAccount.setFpBankAccountOldId(extractOldId(response));
            return extractId(response, "bank account");
        });
        logger.info(
                "cybrilla_workflow operation='create_bank_account' status='completed' local_investor_id='{}' local_bank_id='{}' external_bank_id='{}' fp_old_id='{}'",
                investor.getId(),
                bankAccount.getId(),
                bankAccountId,
                bankAccount.getFpBankAccountOldId());
        bankAccount.setCybrillaBankId(bankAccountId);
    }

    @Override
    public void startBankAccountVerification(Investor investor, InvestorBankAccount bankAccount) {
        if (!StringUtils.hasText(bankAccount.getCybrillaBankId())) {
            throw new CybrillaApiException("Bank account must have a Fintech Primitives bank account id before verification");
        }
        try {
            JsonNode verification = executeWithPoaTokenRetry("create bank account pre verification", () ->
                    postPoa("create_bank_account_pre_verification", "/poa/pre_verifications", bankAccountPreVerificationPayload(investor, bankAccount)));
            JsonNode bankVerification = firstBankAccountVerification(verification);
            bankAccount.setCybrillaBankVerificationId(extractId(verification, "bank account verification"));
            bankAccount.setCybrillaBankVerificationStatus(firstText(verification, "status"));
            bankAccount.setCybrillaBankVerificationConfidence(bankVerification == null ? null : firstText(bankVerification, "code"));
            bankAccount.setExternalSyncPending(false);
            bankAccount.setExternalSyncMessage(null);
            logger.info("cybrilla_workflow operation='create_bank_account_verification' status='completed' local_bank_id='{}' external_verification_id='{}'", bankAccount.getId(), bankAccount.getCybrillaBankVerificationId());
        } catch (CybrillaApiException ex) {
            bankAccount.setExternalSyncPending(true);
            bankAccount.setExternalSyncMessage("Bank account was captured in Fintech Primitives, but POA bank pre-verification could not be started.");
            logger.warn("cybrilla_workflow operation='create_bank_account_verification' status='pending' local_bank_id='{}' external_bank_id='{}' reason='{}'", bankAccount.getId(), bankAccount.getCybrillaBankId(), ex.getMessage());
        }
    }

    @Override
    public JsonNode fetchBankAccountVerification(String bankAccountVerificationId) {
        if (!StringUtils.hasText(bankAccountVerificationId)) {
            throw new CybrillaApiException("Bank account verification id is required");
        }
        if (bankAccountVerificationId.trim().toLowerCase().startsWith("pv_")) {
            return executeWithPoaTokenRetry("fetch bank account pre verification", () ->
                    getPoa("fetch_bank_account_pre_verification", "/poa/pre_verifications/" + bankAccountVerificationId.trim()));
        }
        return executeWithTenantTokenRetry("fetch bank account verification", () ->
                get("fetch_bank_account_verification", "/v2/bank_account_verifications/" + bankAccountVerificationId.trim()));
    }

    @Override
    public JsonNode fetchBankAccountVerificationWithPayloadSnapshot(String bankAccountVerificationId, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_bank_account_verification");
            payloadSnapshot.put("bank_account_verification_id", bankAccountVerificationId);
        }
        return fetchBankAccountVerification(bankAccountVerificationId);
    }

    @Override
    public JsonNode createPreVerification(Map<String, Object> payload) {
        return executeWithPoaTokenRetry("create POA pre verification", () ->
                postPoa("create_poa_pre_verification", "/poa/pre_verifications", payload));
    }

    @Override
    public JsonNode createKycCheck(Investor investor) {
        return createPreVerification(kycValidationPayload(investor));
    }

    @Override
    public JsonNode createCombinedOrderPreVerification(Investor investor, InvestorBankAccount bankAccount) {
        return createPreVerification(combinedOrderPreVerificationPayload(investor, bankAccount));
    }

    @Override
    public JsonNode createReadinessCheck(Investor investor) {
        return createPreVerification(readinessPayload(investor));
    }

    @Override
    public JsonNode fetchKycCheck(String kycCheckId) {
        return executeWithPoaTokenRetry("fetch POA pre verification", () ->
                getPoa("fetch_poa_pre_verification", "/poa/pre_verifications/" + kycCheckId));
    }

    @Override
    public JsonNode refetchKycCheck(String kycCheckId) {
        return fetchKycCheck(kycCheckId);
    }

    @Override
    public JsonNode createKycComplianceCheck(String pan, LocalDate dateOfBirth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(pan)) {
            payload.put("pan", pan.trim().toUpperCase());
        }
        if (dateOfBirth != null) {
            payload.put("date_of_birth", dateOfBirth.toString());
        }
        return executeWithTenantTokenRetry("create KYC compliance check", () ->
                post("create_kyc_compliance_check", "/api/kyc/check", payload));
    }

    @Override
    public JsonNode fetchKycComplianceCheck(String kycComplianceCheckId) {
        return executeWithTenantTokenRetry("fetch KYC compliance check", () ->
                get("fetch_kyc_compliance_check", "/api/kyc/" + kycComplianceCheckId));
    }

    @Override
    public JsonNode refetchKycComplianceCheck(String kycComplianceCheckId) {
        return executeWithTenantTokenRetry("refetch KYC compliance check", () ->
                put("refetch_kyc_compliance_check", "/api/kyc/" + kycComplianceCheckId + "/refetch"));
    }

    @Override
    public JsonNode createKycForm(Map<String, Object> payload) {
        return executeWithPoaTokenRetry("create KYC form", () ->
                postPoa("create_kyc_form", "/poa/kyc_forms", payload));
    }

    @Override
    public JsonNode updateKycForm(String kycFormId, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("id", kycFormId);
        return executeWithPoaTokenRetry("update KYC form", () ->
                patchPoa("update_kyc_form", "/poa/kyc_forms", body));
    }

    @Override
    public JsonNode fetchKycForm(String kycFormId) {
        return executeWithPoaTokenRetry("fetch KYC form", () ->
                getPoa("fetch_kyc_form", "/poa/kyc_forms/" + kycFormId));
    }

    @Override
    public JsonNode uploadKycFormSignature(String kycFormId, byte[] fileBytes, String filename, String contentType) {
        return executeWithPoaTokenRetry("upload KYC form signature", () ->
                postPoaMultipartFile(
                        "upload_kyc_form_signature",
                        "/poa/kyc_forms/" + kycFormId + "/signature",
                        fileBytes,
                        filename,
                        contentType));
    }

    @Override
    public JsonNode retryKycFormProofDetailsFetch(String kycFormId) {
        return executeWithPoaTokenRetry("retry KYC form proof details fetch", () ->
                postPoa("retry_kyc_form_proof_details_fetch", "/poa/kyc_forms/" + kycFormId + "/retry_proof_details_fetch", Map.of()));
    }

    @Override
    public JsonNode listKycRequests(String pan, String status) {
        return executeWithTenantTokenRetry("list KYC requests", () ->
                recordApiRequest("list_kyc_requests", () -> {
                    Map<String, Object> requestPayload = new LinkedHashMap<>();
                    if (StringUtils.hasText(pan)) {
                        requestPayload.put("pan", pan.trim().toUpperCase());
                    }
                    if (StringUtils.hasText(status)) {
                        requestPayload.put("status", status.trim());
                    }
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> {
                                var builder = uriBuilder.path("/v2/kyc_requests");
                                if (requestPayload.containsKey("pan")) {
                                    builder.queryParam("pan", requestPayload.get("pan"));
                                }
                                if (requestPayload.containsKey("status")) {
                                    builder.queryParam("status", requestPayload.get("status"));
                                }
                                return builder.build();
                            })
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "list_kyc_requests",
                            "GET",
                            "/v2/kyc_requests",
                            requestPayload.isEmpty() ? null : requestPayload,
                            response
                    );
                    return response;
                }));
    }

    @Override
    public JsonNode createKycRequest(Map<String, Object> payload) {
        return executeWithTenantTokenRetry("create KYC request", () ->
                post("create_kyc_request", "/v2/kyc_requests", payload));
    }

    @Override
    public JsonNode fetchKycRequest(String kycRequestId) {
        return executeWithTenantTokenRetry("fetch KYC request", () ->
                get("fetch_kyc_request", "/v2/kyc_requests/" + kycRequestId));
    }

    @Override
    public JsonNode updateKycRequest(String kycRequestId, Map<String, Object> payload) {
        return executeWithTenantTokenRetry("update KYC request", () ->
                patch("update_kyc_request", "/v2/kyc_requests/" + kycRequestId, payload));
    }

    @Override
    public JsonNode simulateKycRequest(String kycRequestId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "status", status);
        return executeWithTenantTokenRetry("simulate KYC request", () ->
                post("simulate_kyc_request", "/v2/kyc_requests/" + kycRequestId + "/simulate", payload));
    }

    @Override
    public JsonNode createIdentityDocument(Map<String, Object> payload) {
        return executeWithTenantTokenRetry("create identity document", () ->
                post("create_identity_document", "/v2/identity_documents", payload));
    }

    @Override
    public JsonNode fetchIdentityDocument(String identityDocumentId) {
        return executeWithTenantTokenRetry("fetch identity document", () ->
                get("fetch_identity_document", "/v2/identity_documents/" + identityDocumentId));
    }

    @Override
    public JsonNode listIdentityDocuments(String kycRequestId, String fetchStatus) {
        return executeWithTenantTokenRetry("list identity documents", () ->
                recordApiRequest("list_identity_documents", () -> {
                    Map<String, Object> requestPayload = new LinkedHashMap<>();
                    if (StringUtils.hasText(kycRequestId)) {
                        requestPayload.put("kyc_request", kycRequestId.trim());
                    }
                    if (StringUtils.hasText(fetchStatus)) {
                        requestPayload.put("fetch.status", fetchStatus.trim());
                    }
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> {
                                var builder = uriBuilder.path("/v2/identity_documents");
                                if (requestPayload.containsKey("kyc_request")) {
                                    builder.queryParam("kyc_request", requestPayload.get("kyc_request"));
                                }
                                if (requestPayload.containsKey("fetch.status")) {
                                    builder.queryParam("fetch.status", requestPayload.get("fetch.status"));
                                }
                                return builder.build();
                            })
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "list_identity_documents",
                            "GET",
                            "/v2/identity_documents",
                            requestPayload.isEmpty() ? null : requestPayload,
                            response
                    );
                    return response;
                }));
    }

    @Override
    public JsonNode createEsign(Map<String, Object> payload) {
        return executeWithTenantTokenRetry("create esign", () ->
                post("create_esign", "/v2/esigns", payload));
    }

    @Override
    public JsonNode fetchEsign(String esignId) {
        return executeWithTenantTokenRetry("fetch esign", () ->
                get("fetch_esign", "/v2/esigns/" + esignId));
    }

    @Override
    public SchemeFetchResult fetchProductSchemes() {
        beginTenantTokenScope();
        try {
            // Default poa-mf seeds POA-orderable MF plans (same path the live browse uses);
            // OMS fund_schemes are non-orderable and only fetched when explicitly configured.
            String endpoint = catalogueEndpoint == null ? "poa-mf" : catalogueEndpoint.trim().toLowerCase(Locale.ROOT);
            boolean omsCatalogue = switch (endpoint) {
                case "oms-fund-schemes", "oms", "fund_schemes" -> true;
                default -> false;
            };
            SchemeFetchResult mutualFunds = omsCatalogue
                    ? fetchPaginatedProductCatalogue(
                            "fetch_fund_schemes",
                            "/api/oms/fund_schemes",
                            FUND_SCHEME_MAX_PAGES,
                            this::getFundSchemesPageWithRateLimitRetry)
                    : fetchPaginatedProductCatalogue(
                            "fetch_mf_scheme_plans",
                            "/v2/mf_scheme_plans/" + POA_ORDER_GATEWAY,
                            FUND_SCHEME_MAX_PAGES,
                            this::getMfSchemePlansPageWithRateLimitRetry);
            if (!mutualFunds.complete()) {
                return mutualFunds;
            }

            SchemeFetchResult sifs;
            if (sifCatalogueUnavailable) {
                sifs = SchemeFetchResult.complete(List.of());
            } else {
                sifs = fetchPaginatedProductCatalogue(
                        "fetch_sif_scheme_plans",
                        "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY,
                        FUND_SCHEME_MAX_PAGES,
                        this::getSifSchemePlansPageWithRateLimitRetry
                );
            }
            if (!sifs.complete()) {
                List<ProductScheme> combined = new ArrayList<>(mutualFunds.schemes());
                combined.addAll(sifs.schemes());
                return SchemeFetchResult.partial(combined, sifs.incompleteReason());
            }

            List<ProductScheme> combined = new ArrayList<>(mutualFunds.schemes());
            combined.addAll(sifs.schemes());
            logger.debug(
                    "cybrilla_workflow operation='fetch_product_schemes' status='completed' mf_count='{}' sif_count='{}'",
                    mutualFunds.schemes().size(),
                    sifs.schemes().size()
            );
            return SchemeFetchResult.complete(combined);
        } finally {
            endTenantTokenScope();
        }
    }

    private void beginTenantTokenScope() {
        SCOPED_TENANT_BEARER.set(tokenService.getFinprimTenantAccessToken());
    }

    private void endTenantTokenScope() {
        SCOPED_TENANT_BEARER.remove();
    }

    private void clearScopedTenantBearer() {
        SCOPED_TENANT_BEARER.remove();
    }

    @FunctionalInterface
    private interface SchemePageFetcher {
        JsonNode fetch(int page, int size, Map<String, Object> requestSnapshot);
    }

    /**
     * Paginated Finprim catalogue fetch shared by OMS mutual funds and POA SIF scheme plans.
     */
    private SchemeFetchResult fetchPaginatedProductCatalogue(
            String operation,
            String path,
            int maxPages,
            SchemePageFetcher pageFetcher
    ) {
        int page = 0;
        int size = FUND_SCHEME_PAGE_SIZE;
        List<ProductScheme> schemes = new ArrayList<>();
        boolean truncatedAtPageCap = false;

        while (true) {
            int currentPage = page;
            Map<String, Object> requestSnapshot = new LinkedHashMap<>();
            JsonNode response;
            try {
                response = executeWithTenantTokenRetry(
                        operation + " page " + currentPage,
                        () -> pageFetcher.fetch(currentPage, size, requestSnapshot)
                );
            } catch (CybrillaApiException ex) {
                if (path.contains("sif_scheme_plans") && isNotFound(ex)) {
                    sifCatalogueUnavailable = true;
                    logger.info(
                            "cybrilla_workflow operation='{}' status='skipped' reason='sif_catalogue_not_enabled' path='{}'",
                            operation,
                            path
                    );
                    return SchemeFetchResult.complete(List.of());
                }
                throw ex;
            }
            JsonNode schemeNodes = extractSchemeArray(response);
            int pageCount = 0;
            String requestJson = writeJson(requestSnapshot);

            for (JsonNode schemeNode : schemeNodes) {
                schemes.add(toProductScheme(schemeNode, requestJson));
                pageCount++;
            }

            logger.debug("cybrilla_workflow operation='{}' status='page_loaded' page='{}' count='{}'", operation, page, pageCount);

            boolean naturalEnd = pageCount < size || isLastPage(response);
            if (naturalEnd) {
                break;
            }
            if (page >= maxPages - 1) {
                truncatedAtPageCap = true;
                logger.warn(
                        "cybrilla_workflow operation='{}' status='truncated_at_page_cap' page='{}' max_pages='{}' "
                                + "page_size='{}' partial_total='{}'",
                        operation,
                        page,
                        maxPages,
                        size,
                        schemes.size()
                );
                break;
            }
            page++;
        }

        if (truncatedAtPageCap) {
            return SchemeFetchResult.partial(schemes, "max_pages_reached");
        }
        logger.debug("cybrilla_workflow operation='{}' status='completed' total_count='{}'", operation, schemes.size());
        return SchemeFetchResult.complete(schemes);
    }

    @Override
    public String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before order creation");
        }
        if (!StringUtils.hasText(investor.getExternalMfInvestmentAccountId())) {
            throw new CybrillaApiException("Investor must have a Fintech Primitives MF investment account id before order creation");
        }

        return executeWithTenantTokenRetry("create order", () -> {
            boolean sipOrder = order.getTransactionType() == TransactionType.SIP;
            JsonNode response = post(
                    sipOrder ? "create_mf_purchase_plan" : "create_mf_purchase",
                    sipOrder ? MF_PURCHASE_PLANS_PATH : MF_PURCHASES_PATH,
                    sipOrder ? mfPurchasePlanPayload(order, investor, productScheme) : mfPurchasePayload(order, investor, productScheme),
                    idempotencyKey("order", order.getId())
            );
            return extractId(response, "order");
        });
    }

    @Override
    public String generateInvestorActionUrl(TransactionOrder order) {
        if (StringUtils.hasText(order.getInvestorActionUrl()) && order.getInvestorActionUrl().startsWith("http")) {
            return order.getInvestorActionUrl();
        }
        String token = StringUtils.hasText(order.getInvestorActionToken())
                ? order.getInvestorActionToken()
                : String.valueOf(order.getId());
        return "/investor-actions/" + token;
    }

    @Override
    public IfscLookupResult fetchIfscDetails(String ifscCode) {
        if (!StringUtils.hasText(ifscCode)) {
            throw new CybrillaApiException("IFSC code is required");
        }
        String normalized = ifscCode.trim().toUpperCase();
        JsonNode response = executeWithTenantTokenRetry("fetch ifsc details", () ->
                get("fetch_ifsc_details", IFSC_LOOKUP_PATH + normalized));
        return new IfscLookupResult(
                firstText(response, "ifsc_code", "ifsc"),
                firstText(response, "bank_name"),
                firstText(response, "branch_name"),
                firstText(response, "branch_address"),
                firstText(response, "city"),
                firstText(response, "district"),
                firstText(response, "state"),
                firstText(response, "micr_code")
        );
    }

    @Override
    public PincodeLookupResult fetchPincodeDetails(String pincode) {
        if (!StringUtils.hasText(pincode)) {
            throw new CybrillaApiException("Pincode is required");
        }
        String normalized = pincode.trim().replaceAll("\\D", "");
        if (!normalized.matches("\\d{6}")) {
            throw new CybrillaApiException("Pincode must be a 6-digit number");
        }
        JsonNode response = executeWithTenantTokenRetry("fetch pincode details", () ->
                get("fetch_pincode_details", PINCODE_LOOKUP_PATH + normalized));
        return mapPincodeLookupResult(response, normalized);
    }

    private PincodeLookupResult mapPincodeLookupResult(JsonNode response, String fallbackCode) {
        String code = firstText(response, "code", "pincode");
        if (!StringUtils.hasText(code)) {
            code = fallbackCode;
        }
        String rawCity = firstText(response, "city");
        String rawDistrict = firstText(response, "district");
        String rawState = firstText(response, "state_name", "state");
        String countryAnsiCode = firstText(response, "country_ansi_code");
        java.util.List<String> rawCities = new java.util.ArrayList<>();
        if (response != null && response.has("cities") && response.get("cities").isArray()) {
            response.get("cities").forEach(node -> {
                if (node.isTextual() && StringUtils.hasText(node.asText())) {
                    rawCities.add(node.asText().trim());
                }
            });
        }
        String district = com.platizio.wealthtech.common.LocationLabelSanitizer.sanitize(rawDistrict);
        String city = com.platizio.wealthtech.common.LocationLabelSanitizer.resolveCityLabel(rawCity, rawDistrict);
        String stateName = com.platizio.wealthtech.common.LocationLabelSanitizer.normalizeStateLabel(rawState);
        java.util.List<String> cities = com.platizio.wealthtech.common.LocationLabelSanitizer.sanitizeCityOptions(
                rawCity,
                rawDistrict,
                rawCities
        );
        return new PincodeLookupResult(code, city, district, stateName, countryAnsiCode, cities);
    }

    @Override
    public JsonNode fetchMfPurchase(String mfPurchaseId) {
        if (!StringUtils.hasText(mfPurchaseId)) {
            throw new CybrillaApiException("MF purchase id is required");
        }
        return executeWithTenantTokenRetry("fetch mf purchase", () ->
                get("fetch_mf_purchase", MF_PURCHASES_PATH + "/" + mfPurchaseId.trim()));
    }

    @Override
    public JsonNode updateMfPurchaseConsent(String mfPurchaseId, Map<String, Object> consent) {
        if (!StringUtils.hasText(mfPurchaseId)) {
            throw new CybrillaApiException("MF purchase id is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", mfPurchaseId.trim());
        payload.put("consent", consent);
        return executeWithTenantTokenRetry("update mf purchase consent", () ->
                patch("update_mf_purchase_consent", MF_PURCHASES_PATH, payload));
    }

    @Override
    public JsonNode createNetbankingPayment(List<Integer> amcOrderIds, String paymentPostbackUrl, String paymentMethod) {
        return createNetbankingPayment(amcOrderIds, paymentPostbackUrl, paymentMethod, null, null);
    }

    @Override
    public JsonNode createNetbankingPayment(
            List<Integer> amcOrderIds,
            String paymentPostbackUrl,
            String paymentMethod,
            Integer bankAccountOldId,
            String providerName
    ) {
        if (amcOrderIds == null || amcOrderIds.isEmpty()) {
            throw new CybrillaApiException("At least one AMC order id is required to create a payment");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amc_order_ids", amcOrderIds);
        if (StringUtils.hasText(paymentPostbackUrl)) {
            payload.put("payment_postback_url", paymentPostbackUrl);
        }
        if (StringUtils.hasText(paymentMethod)) {
            payload.put("method", paymentMethod);
        }
        if (bankAccountOldId != null && bankAccountOldId > 0) {
            payload.put("bank_account_id", bankAccountOldId);
        }
        String resolvedProvider = StringUtils.hasText(providerName) ? providerName.trim() : CYBRILLAPOA_PAYMENT_PROVIDER;
        payload.put("provider_name", resolvedProvider);
        return executeWithTenantTokenRetry("create netbanking payment", () ->
                post("create_netbanking_payment", NETBANKING_PAYMENT_PATH, payload));
    }

    @Override
    public JsonNode fetchPayment(int paymentId) {
        if (paymentId <= 0) {
            throw new CybrillaApiException("Payment id is required");
        }
        return executeWithTenantTokenRetry("fetch payment", () ->
                get("fetch_payment", PAYMENTS_PATH + "/" + paymentId));
    }

    @Override
    public JsonNode simulatePayment(int paymentId, String status) {
        if (paymentId <= 0) {
            throw new CybrillaApiException("Payment id is required");
        }
        Map<String, Object> payload = Map.of("status", status == null ? "SUCCESS" : status.trim());
        return executeWithTenantTokenRetry("simulate payment", () ->
                post("simulate_payment", SIMULATE_PAYMENTS_PATH + "/" + paymentId, payload));
    }

    @Override
    public JsonNode confirmMfPurchase(String mfPurchaseId) {
        if (!StringUtils.hasText(mfPurchaseId)) {
            throw new CybrillaApiException("MF purchase id is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", mfPurchaseId.trim());
        payload.put("state", "confirmed");
        return executeWithTenantTokenRetry("confirm mf purchase", () ->
                patch("confirm_mf_purchase", MF_PURCHASES_PATH, payload));
    }

    @Override
    public JsonNode fetchBankAccount(String bankAccountId) {
        if (!StringUtils.hasText(bankAccountId)) {
            throw new CybrillaApiException("Bank account id is required");
        }
        return executeWithTenantTokenRetry("fetch bank account", () ->
                get("fetch_bank_account", BANK_ACCOUNTS_PATH + "/" + bankAccountId.trim()));
    }

    @Override
    public JsonNode createMandate(int bankAccountOldId, String mandateType, int mandateLimit, String providerName) {
        if (bankAccountOldId <= 0) {
            throw new CybrillaApiException("FP bank account old_id is required to create a mandate");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandate_type", mandateType);
        payload.put("bank_account_id", bankAccountOldId);
        payload.put("mandate_limit", mandateLimit);
        if (StringUtils.hasText(providerName)) {
            payload.put("provider_name", providerName.trim());
        }
        return executeWithTenantTokenRetry("create mandate", () ->
                post("create_mandate", MANDATES_PATH, payload));
    }

    @Override
    public JsonNode authorizeMandate(int mandateId, String paymentPostbackUrl) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Mandate id is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandate_id", mandateId);
        if (StringUtils.hasText(paymentPostbackUrl)) {
            payload.put("payment_postback_url", paymentPostbackUrl);
        }
        return executeWithTenantTokenRetry("authorize mandate", () ->
                post("authorize_mandate", EMANDATE_AUTH_PATH, payload));
    }

    @Override
    public JsonNode fetchMandate(int mandateId) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Mandate id is required");
        }
        return executeWithTenantTokenRetry("fetch mandate", () ->
                get("fetch_mandate", MANDATES_PATH + "/" + mandateId));
    }

    @Override
    public JsonNode simulateMandate(int mandateId, String status) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Mandate id is required");
        }
        Map<String, Object> payload = Map.of("status", status == null ? "APPROVED" : status.trim());
        return executeWithTenantTokenRetry("simulate mandate", () ->
                post("simulate_mandate", SIMULATE_MANDATES_PATH + "/" + mandateId, payload));
    }

    @Override
    public JsonNode fetchMfPurchasePlan(String mfPurchasePlanId) {
        if (!StringUtils.hasText(mfPurchasePlanId)) {
            throw new CybrillaApiException("MF purchase plan id is required");
        }
        return executeWithTenantTokenRetry("fetch mf purchase plan", () ->
                get("fetch_mf_purchase_plan", MF_PURCHASE_PLANS_PATH + "/" + mfPurchasePlanId.trim()));
    }

    @Override
    public JsonNode updateMfPurchasePlan(String mfPurchasePlanId, Map<String, Object> updatePayload) {
        if (!StringUtils.hasText(mfPurchasePlanId)) {
            throw new CybrillaApiException("MF purchase plan id is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>(updatePayload == null ? Map.of() : updatePayload);
        payload.put("id", mfPurchasePlanId.trim());
        return executeWithTenantTokenRetry("update mf purchase plan", () ->
                patch("update_mf_purchase_plan", MF_PURCHASE_PLANS_PATH, payload));
    }

    @Override
    public JsonNode listMfPurchasesForPlan(String mfPurchasePlanId) {
        if (!StringUtils.hasText(mfPurchasePlanId)) {
            throw new CybrillaApiException("MF purchase plan id is required");
        }
        return executeWithTenantTokenRetry("list mf purchases for plan", () ->
                get("list_mf_purchases_for_plan", MF_PURCHASES_PATH + "?plan=" + mfPurchasePlanId.trim()));
    }

    @Override
    public JsonNode createNachPayment(int mandateId, List<Integer> amcOrderIds) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Mandate id is required");
        }
        if (amcOrderIds == null || amcOrderIds.isEmpty()) {
            throw new CybrillaApiException("At least one AMC order id is required to create a NACH payment");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandate_id", mandateId);
        payload.put("amc_order_ids", amcOrderIds);
        return executeWithTenantTokenRetry("create nach payment", () ->
                post("create_nach_payment", NACH_PAYMENT_PATH, payload));
    }

    @Override
    public String createSipOrderWithMandate(TransactionOrder order, Investor investor, ProductScheme productScheme, int mandateId) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Approved mandate id is required before SIP plan creation");
        }
        return executeWithTenantTokenRetry("create sip order with mandate", () -> {
            JsonNode response = post(
                    "create_mf_purchase_plan",
                    MF_PURCHASE_PLANS_PATH,
                    mfPurchasePlanWithMandatePayload(order, investor, productScheme, mandateId),
                    idempotencyKey("order", order.getId())
            );
            return extractId(response, "order");
        });
    }

    @Override
    public String createRedemption(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new CybrillaApiException("Order must have a Cybrilla/FP external order id before redemption");
        }
        if (!StringUtils.hasText(investor.getExternalMfInvestmentAccountId())) {
            throw new CybrillaApiException("Investor must have a Fintech Primitives MF investment account id before redemption");
        }

        return executeWithTenantTokenRetry("create redemption", () -> {
            JsonNode response = post(
                    "create_mf_redemption",
                    MF_REDEMPTIONS_PATH,
                    redemptionPayload(order, investor, productScheme),
                    idempotencyKey("redemption", order.getId())
            );
            return extractId(response, "redemption");
        });
    }

    @Override
    public JsonNode cancelPurchasePlan(String planId, String cancellationCode, String cancellationReason) {
        if (!StringUtils.hasText(planId)) {
            throw new CybrillaApiException("Fintech Primitives purchase plan id is required to cancel a SIP");
        }
        String code = StringUtils.hasText(cancellationCode) ? cancellationCode.trim() : "invest_later";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", planId.trim());
        payload.put("cancellation_code", code);
        if ("custom_reason".equalsIgnoreCase(code) && StringUtils.hasText(cancellationReason)) {
            payload.put("cancellation_reason", cancellationReason.trim());
        }
        return executeWithTenantTokenRetry("cancel purchase plan", () -> {
            JsonNode response = post(
                    "cancel_mf_purchase_plan",
                    MF_PURCHASE_PLANS_PATH + "/cancel",
                    payload,
                    "cancel-plan-" + planId.trim()
            );
            logger.info(
                    "cybrilla_workflow operation='cancel_purchase_plan' status='completed' plan_id='{}' fp_state='{}'",
                    planId,
                    response.path("state").asText("")
            );
            return response;
        });
    }

    @Override
    public void cancelOrder(TransactionOrder order) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            logger.warn("cybrilla_workflow operation='cancel_order' status='skipped' reason='missing_external_order_id' local_order_id='{}'", order.getId());
            return;
        }
        if (order.getTransactionType() == TransactionType.SIP) {
            cancelPurchasePlan(order.getExternalOrderId(), "invest_later", null);
            return;
        }
        executeWithTenantTokenRetry("cancel lumpsum purchase", () -> {
            post(
                    "cancel_mf_purchase",
                    MF_PURCHASES_PATH + "/" + order.getExternalOrderId() + "/cancel",
                    idempotencyKey("cancel-order", order.getId())
            );
            return null;
        });
    }

    private void syncInvestorContactResourcesForProfile(String profileId, Investor investor) {
        ensureInvestorContactResources(profileId, investor);
    }

    private void ensureInvestorContactResources(String profileId, Investor investor) {
        if (!StringUtils.hasText(profileId)) {
            return;
        }
        if (!StringUtils.hasText(latestProfileLinkedResourceId("/v2/addresses", profileId))) {
            createAddressIfPresent(profileId, investor);
        }
        ensureGatewayReadyEmailResource(profileId, investor);
        if (!StringUtils.hasText(latestProfileLinkedResourceId("/v2/phone_numbers", profileId))) {
            createPhoneIfPresent(profileId, investor);
        }
    }

    private void ensureGatewayReadyEmailResource(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getEmail()) || !isGatewayAcceptableEmail(investor.getEmail())) {
            if (StringUtils.hasText(investor.getEmail()) && !isGatewayAcceptableEmail(investor.getEmail())) {
                logger.warn(
                        "cybrilla_workflow operation='ensure_investor_email' status='skipped_invalid_tld' local_investor_id='{}' external_profile_id='{}'",
                        investor.getId(),
                        profileId);
            }
            return;
        }
        String normalizedLocalEmail = investor.getEmail().trim().toLowerCase(Locale.ROOT);
        if (profileLinksEmailAddress(profileId, normalizedLocalEmail)) {
            return;
        }
        if (!StringUtils.hasText(latestProfileLinkedResourceId("/v2/email_addresses", profileId))) {
            createEmailIfPresent(profileId, investor);
            return;
        }
        logger.info(
                "cybrilla_workflow operation='ensure_investor_email' status='creating_gateway_email' local_investor_id='{}' external_profile_id='{}'",
                investor.getId(),
                profileId);
        createEmailIfPresent(profileId, investor);
    }

    private boolean isGatewayAcceptableEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return false;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return !domain.equals("local")
                && !domain.endsWith(".local")
                && !domain.equals("test")
                && !domain.endsWith(".test")
                && !domain.equals("invalid")
                && !domain.endsWith(".invalid");
    }

    private boolean profileLinksEmailAddress(String profileId, String normalizedEmail) {
        if (!StringUtils.hasText(profileId) || !StringUtils.hasText(normalizedEmail)) {
            return false;
        }
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor email addresses", () ->
                    recordApiRequest("list_profile_email_addresses", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v2/email_addresses")
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_email_addresses",
                                "GET",
                                "/v2/email_addresses",
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return false;
            }
            for (JsonNode item : data) {
                String linkedEmail = item.path("email").asText("");
                if (normalizedEmail.equalsIgnoreCase(linkedEmail.trim())) {
                    return true;
                }
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='list_profile_email_addresses' status='failed' external_profile_id='{}' reason='{}'",
                    profileId,
                    ex.getMessage());
        }
        return false;
    }

    private String resolveFolioDefaultEmailId(String profileId, Investor investor) {
        if (StringUtils.hasText(investor.getEmail()) && isGatewayAcceptableEmail(investor.getEmail())) {
            String matched = findProfileLinkedEmailResourceId(profileId, investor.getEmail().trim());
            if (StringUtils.hasText(matched)) {
                return matched;
            }
        }
        return firstGatewayAcceptableEmailResourceId(profileId);
    }

    private String resolveFolioDefaultPhoneId(String profileId, Investor investor) {
        if (StringUtils.hasText(investor.getMobileNumber())) {
            String matched = findProfileLinkedPhoneResourceId(profileId, investor.getMobileNumber());
            if (StringUtils.hasText(matched)) {
                return matched;
            }
        }
        return latestProfileLinkedResourceId("/v2/phone_numbers", profileId);
    }

    private String findProfileLinkedEmailResourceId(String profileId, String email) {
        if (!StringUtils.hasText(profileId) || !StringUtils.hasText(email)) {
            return null;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor email addresses", () ->
                    recordApiRequest("list_profile_email_addresses", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v2/email_addresses")
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_email_addresses",
                                "GET",
                                "/v2/email_addresses",
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            String fallback = null;
            for (JsonNode item : data) {
                String resourceId = item.path("id").asText(null);
                String linkedEmail = item.path("email").asText("");
                if (!StringUtils.hasText(resourceId) || !StringUtils.hasText(linkedEmail)) {
                    continue;
                }
                if (normalizedEmail.equalsIgnoreCase(linkedEmail.trim())) {
                    return resourceId;
                }
                if (fallback == null && isGatewayAcceptableEmail(linkedEmail)) {
                    fallback = resourceId;
                }
            }
            return fallback;
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='find_profile_email' status='failed' external_profile_id='{}' reason='{}'",
                    profileId,
                    ex.getMessage());
        }
        return null;
    }

    private String firstGatewayAcceptableEmailResourceId(String profileId) {
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor email addresses", () ->
                    recordApiRequest("list_profile_email_addresses", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v2/email_addresses")
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_email_addresses",
                                "GET",
                                "/v2/email_addresses",
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            for (JsonNode item : data) {
                String linkedEmail = item.path("email").asText("");
                if (isGatewayAcceptableEmail(linkedEmail)) {
                    return item.path("id").asText(null);
                }
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='first_gateway_email' status='failed' external_profile_id='{}' reason='{}'",
                    profileId,
                    ex.getMessage());
        }
        return null;
    }

    private String findProfileLinkedPhoneResourceId(String profileId, String mobileNumber) {
        if (!StringUtils.hasText(profileId) || !StringUtils.hasText(mobileNumber)) {
            return null;
        }
        PhoneParts localPhone = PhoneParts.from(mobileNumber);
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor phone numbers", () ->
                    recordApiRequest("list_profile_phone_numbers", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/v2/phone_numbers")
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_phone_numbers",
                                "GET",
                                "/v2/phone_numbers",
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            for (JsonNode item : data) {
                String resourceId = item.path("id").asText(null);
                String isd = item.path("isd").asText("").replace("+", "").trim();
                String number = item.path("number").asText("").trim();
                if (!StringUtils.hasText(resourceId)) {
                    continue;
                }
                if (localPhone.number().equals(number)
                        && (localPhone.isd().equals(isd) || ("91".equals(localPhone.isd()) && isd.isEmpty()))) {
                    return resourceId;
                }
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='find_profile_phone' status='failed' external_profile_id='{}' reason='{}'",
                    profileId,
                    ex.getMessage());
        }
        return null;
    }

    private String findInvestorProfileIdByPan(String pan) {
        if (!StringUtils.hasText(pan)) {
            return null;
        }
        try {
            JsonNode response = listInvestorProfiles(pan.trim(), "individual");
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray()) {
                return null;
            }
            String normalizedPan = pan.trim().toUpperCase(Locale.ROOT);
            for (JsonNode profile : data) {
                String profilePan = profile.path("pan").asText("");
                if (normalizedPan.equalsIgnoreCase(profilePan)) {
                    return profile.path("id").asText(null);
                }
            }
            if (!data.isEmpty()) {
                return data.get(0).path("id").asText(null);
            }
        } catch (CybrillaApiException ex) {
            logger.warn("cybrilla_workflow operation='resolve_investor_profile' status='failed' reason='{}'", ex.getMessage());
        }
        return null;
    }

    private String firstProfileLinkedResourceId(String resourcePath, String profileId) {
        return profileLinkedResourceId(resourcePath, profileId, false);
    }

    private String latestProfileLinkedResourceId(String resourcePath, String profileId) {
        return profileLinkedResourceId(resourcePath, profileId, true);
    }

    private String profileLinkedResourceId(String resourcePath, String profileId, boolean preferLatest) {
        if (!StringUtils.hasText(profileId)) {
            return null;
        }
        try {
            JsonNode response = executeWithTenantTokenRetry("list investor profile resources", () ->
                    recordApiRequest("list_profile_resources", () -> {
                        JsonNode body = restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .path(resourcePath)
                                        .queryParam("profile", profileId.trim())
                                        .build())
                                .headers(this::setTenantAuthHeaders)
                                .retrieve()
                                .body(JsonNode.class);
                        externalApiSnapshotService.recordSuccess(
                                "finprim",
                                "list_profile_resources",
                                "GET",
                                resourcePath,
                                Map.of("profile", profileId),
                                body);
                        return body;
                    }));
            JsonNode data = response == null ? null : response.path("data");
            if (data != null && data.isArray()) {
                String matched = null;
                for (JsonNode item : data) {
                    String linkedProfile = item.path("profile").asText("");
                    if (profileId.trim().equalsIgnoreCase(linkedProfile)) {
                        matched = item.path("id").asText(null);
                        if (!preferLatest) {
                            return matched;
                        }
                    }
                }
                return matched;
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "cybrilla_workflow operation='list_profile_resources' status='failed' path='{}' profile_id='{}' reason='{}'",
                    resourcePath,
                    profileId,
                    ex.getMessage());
        }
        return null;
    }

    private String findMfInvestmentAccountId(String investorProfileId) {
        if (!StringUtils.hasText(investorProfileId)) {
            return null;
        }
        try {
            JsonNode response = listMfInvestmentAccounts(investorProfileId);
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                return null;
            }
            String expectedProfileId = investorProfileId.trim();
            for (JsonNode account : data) {
                String accountId = account.path("id").asText(null);
                if (!StringUtils.hasText(accountId)) {
                    continue;
                }
                String primaryInvestor = account.path("primary_investor").asText("");
                // FP list filtered by ?primary_investor= often omits primary_investor on each row.
                if (!StringUtils.hasText(primaryInvestor) || expectedProfileId.equalsIgnoreCase(primaryInvestor)) {
                    return accountId;
                }
            }
            return null;
        } catch (CybrillaApiException ex) {
            logger.warn("cybrilla_workflow operation='resolve_mf_investment_account' status='failed' reason='{}'", ex.getMessage());
        }
        return null;
    }

    private static boolean isDuplicateMfInvestmentAccountError(CybrillaApiException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("already present")
                || lower.contains("investment account already");
    }

    private void createAddressIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getAddressLine1()) || !StringUtils.hasText(investor.getPostalCode())) {
            return;
        }

        executeWithTenantTokenRetry("create investor address", () -> {
            post("create_investor_address", "/v2/addresses", addressPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_address' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createEmailIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getEmail())) {
            return;
        }

        executeWithTenantTokenRetry("create investor email", () -> {
            post("create_investor_email", "/v2/email_addresses", emailPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_email' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createPhoneIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            return;
        }

        executeWithTenantTokenRetry("create investor phone number", () -> {
            post("create_investor_phone", "/v2/phone_numbers", phonePayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_phone' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private JsonNode post(String operation, String path, Map<String, Object> payload) {
        return post(operation, path, payload, null);
    }

    private JsonNode postPoa(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_poa' method='POST' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setPoaAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "POST", path, payload, response);
                    return response;
                });
    }

    private JsonNode patchPoa(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_poa' method='PATCH' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setPoaAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "PATCH", path, payload, response);
                    return response;
                });
    }

    private JsonNode postPoaMultipartFile(String operation, String path, byte[] fileBytes, String filename, String contentType) {
        logger.info("cybrilla_api direction='backend_to_poa' method='POST' path='{}' multipart_file='{}'", path, filename);
        return recordApiRequest(operation, () -> {
                    MultipartBodyBuilder builder = new MultipartBodyBuilder();
                    builder.part("file", new ByteArrayResource(fileBytes) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    }).contentType(MediaType.parseMediaType(contentType));
                    MultiValueMap<String, HttpEntity<?>> body = builder.build();
                    JsonNode response = poaRestClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(this::setPoaAuthHeaders)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "POST", path, Map.of("file", filename), response);
                    return response;
                });
    }

    private JsonNode post(String operation, String path, Map<String, Object> payload, String idempotencyKey) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='POST' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> setTenantAuthHeaders(headers, idempotencyKey))
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "POST", path, payload, response);
                    return response;
                });
    }

    private JsonNode post(String operation, String path, String idempotencyKey) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='POST' path='{}' payload_fields='none'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.post()
                    .uri(path)
                    .headers(headers -> setTenantAuthHeaders(headers, idempotencyKey))
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "POST", path, null, response);
                    return response;
                });
    }

    private JsonNode patch(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='PATCH' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setTenantAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "PATCH", path, payload, response);
                    return response;
                });
    }

    private JsonNode put(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='PUT' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.put()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "PUT", path, null, response);
                    return response;
                });
    }

    private JsonNode get(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='GET' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.get()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "GET", path, null, response);
                    return response;
                });
    }

    private JsonNode getPoa(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_poa' method='GET' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.get()
                    .uri(path)
                    .headers(this::setPoaAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "GET", path, null, response);
                    return response;
                });
    }

    private void delete(String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='DELETE' path='{}'", path);
        recordApiRequest("cancel_order", () -> {
                    var response = restClient.delete()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .toBodilessEntity();
                    externalApiSnapshotService.recordSuccess("finprim", "cancel_order", "DELETE", path, null, null);
                    return response;
                });
    }

    private JsonNode getFundSchemesPage(int page, int size) {
        logger.debug("cybrilla_api direction='backend_to_finprim' method='GET' path='/api/oms/fund_schemes' page='{}' size='{}'", page, size);
        return recordApiRequest("fetch_fund_schemes", () -> {
                    JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/oms/fund_schemes")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "fetch_fund_schemes",
                            "GET",
                            "/api/oms/fund_schemes",
                            Map.of("page", page, "size", size),
                            response
                    );
                    return response;
                });
    }

    @Override
    public JsonNode getFundSchemesPageWithPayloadSnapshot(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_fund_schemes_page");
            payloadSnapshot.put("path", "/api/oms/fund_schemes");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
        }
        return getFundSchemesPage(page, size);
    }

    @Override
    public LiveCataloguePage fetchLiveCataloguePage(String endpoint, int page, int size) {
        beginTenantTokenScope();
        try {
            String normalized = endpoint == null ? "poa-mf" : endpoint.trim().toLowerCase();
            return switch (normalized) {
                case "oms-fund-schemes", "oms", "fund_schemes" -> fetchLiveCataloguePageInternal(
                        "fetch_fund_schemes",
                        "/api/oms/fund_schemes",
                        page,
                        size,
                        this::getFundSchemesPageWithRateLimitRetry
                );
                case "sif-poa", "sif" -> fetchLiveCataloguePageInternal(
                        "fetch_sif_scheme_plans",
                        "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY,
                        page,
                        size,
                        this::getSifSchemePlansPageWithRateLimitRetry
                );
                default -> fetchLiveCataloguePageInternal(
                        "fetch_mf_scheme_plans",
                        "/v2/mf_scheme_plans/" + POA_ORDER_GATEWAY,
                        page,
                        size,
                        this::getMfSchemePlansPageWithRateLimitRetry
                );
            };
        } finally {
            endTenantTokenScope();
        }
    }

    private LiveCataloguePage fetchLiveCataloguePageInternal(
            String operation,
            String path,
            int page,
            int size,
            SchemePageFetcher pageFetcher
    ) {
        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        JsonNode response = executeWithTenantTokenRetry(
                operation + " page " + page,
                () -> pageFetcher.fetch(page, size, requestSnapshot)
        );
        String requestJson = writeJson(requestSnapshot);
        JsonNode schemeNodes = extractSchemeArray(response);
        List<ProductScheme> schemes = new ArrayList<>();
        for (JsonNode schemeNode : schemeNodes) {
            ProductScheme mapped = toProductScheme(schemeNode, requestJson);
            if (StringUtils.hasText(mapped.getExternalIsin()) && StringUtils.hasText(mapped.getSchemeName())) {
                schemes.add(mapped);
            } else {
                logger.warn(
                        "product_scheme_map status='skipped' reason='missing_isin_or_name' external_code='{}' scheme_name='{}'",
                        mapped.getExternalSchemeCode(),
                        mapped.getSchemeName());
            }
        }
        long totalElements = resolveTotalElements(response, schemes.size());
        logger.info(
                "cybrilla_workflow operation='{}' status='live_page' path='{}' page='{}' size='{}' count='{}' total='{}'",
                operation,
                path,
                page,
                size,
                schemes.size(),
                totalElements
        );
        return new LiveCataloguePage(response, schemes, totalElements, page, size, path);
    }

    private JsonNode getMfSchemePlansPage(int page, int size) {
        String path = "/v2/mf_scheme_plans/" + POA_ORDER_GATEWAY;
        logger.debug("cybrilla_api direction='backend_to_finprim' method='GET' path='{}' page='{}' size='{}'", path, page, size);
        return recordApiRequest("fetch_mf_scheme_plans", () -> {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("expand", "mf_scheme,mf_fund")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
            externalApiSnapshotService.recordSuccess(
                    "finprim",
                    "fetch_mf_scheme_plans",
                    "GET",
                    path,
                    Map.of("page", page, "size", size, "expand", "mf_scheme,mf_fund"),
                    response
            );
            return response;
        });
    }

    private JsonNode getMfSchemePlansPageWithRateLimitRetry(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_mf_scheme_plans_page");
            payloadSnapshot.put("path", "/v2/mf_scheme_plans/" + POA_ORDER_GATEWAY);
            payloadSnapshot.put("expand", "mf_scheme,mf_fund");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
        }
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getMfSchemePlansPage(page, size);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/v2/mf_scheme_plans/{}' page='{}' attempt='{}' retry_in_ms='{}'",
                        POA_ORDER_GATEWAY,
                        page,
                        attempt,
                        delayMillis
                );
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable MF scheme plan retry state");
    }

    private long resolveTotalElements(JsonNode response, int fallbackCount) {
        if (response == null || response.isNull()) {
            return fallbackCount;
        }
        long total = firstLong(response, "totalElements", "total_elements", "total");
        if (total >= 0) {
            return total;
        }
        return fallbackCount;
    }

    private long firstLong(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull() && value.isNumber()) {
                return value.asLong();
            }
        }
        return -1L;
    }

    private JsonNode getSifSchemePlansPage(int page, int size) {
        String path = "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY;
        logger.debug("cybrilla_api direction='backend_to_finprim' method='GET' path='{}' page='{}' size='{}'", path, page, size);
        return recordApiRequest("fetch_sif_scheme_plans", () -> {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("expand", "sif_scheme,sif_fund")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
            externalApiSnapshotService.recordSuccess(
                    "finprim",
                    "fetch_sif_scheme_plans",
                    "GET",
                    path,
                    Map.of("page", page, "size", size, "expand", "sif_scheme,sif_fund"),
                    response
            );
            return response;
        });
    }

    private JsonNode getSifSchemePlansPageWithRateLimitRetry(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_sif_scheme_plans_page");
            payloadSnapshot.put("path", "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY);
            payloadSnapshot.put("expand", "sif_scheme,sif_fund");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
        }
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getSifSchemePlansPage(page, size);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/v2/sif_scheme_plans/{}' page='{}' attempt='{}' retry_in_ms='{}'",
                        POA_ORDER_GATEWAY,
                        page,
                        attempt,
                        delayMillis
                );
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable SIF scheme plan retry state");
    }

    private <T> T recordApiRequest(String operation, Supplier<T> requestSupplier) {
        return Timer.builder("cybrilla.api.request")
                .description("Latency of Cybrilla/Fintech Primitives API requests")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(requestSupplier);
    }

    private JsonNode getFundSchemesPageWithRateLimitRetry(int page, int size, Map<String, Object> requestSnapshot) {
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getFundSchemesPageWithPayloadSnapshot(page, size, requestSnapshot);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/api/oms/fund_schemes' page='{}' attempt='{}' retry_in_ms='{}'",
                        page,
                        attempt,
                        delayMillis
                );
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable fund scheme retry state");
    }

    private long resolveRateLimitDelayMillis(RestClientResponseException ex, int attempt) {
        if (ex.getResponseHeaders() != null) {
            String retryAfter = ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (StringUtils.hasText(retryAfter) && retryAfter.matches("\\d+")) {
                return Long.parseLong(retryAfter) * 1_000L;
            }
        }
        return FUND_SCHEME_RATE_LIMIT_BACKOFF_MILLIS * attempt;
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CybrillaApiException("Interrupted while waiting to retry rate-limited fund schemes request", ex);
        }
    }

    private void setTenantAuthHeaders(HttpHeaders headers) {
        setTenantAuthHeaders(headers, null);
    }

    private void setTenantAuthHeaders(HttpHeaders headers, String idempotencyKey) {
        String bearer = SCOPED_TENANT_BEARER.get();
        if (!StringUtils.hasText(bearer)) {
            bearer = tokenService.getFinprimTenantAccessToken();
        }
        headers.setBearerAuth(bearer);
        if (StringUtils.hasText(finprimProperties.tenantHeaderValue())) {
            headers.set(TENANT_HEADER, finprimProperties.tenantHeaderValue());
        }
        if (StringUtils.hasText(idempotencyKey)) {
            headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
    }

    private void setPoaAuthHeaders(HttpHeaders headers) {
        headers.setBearerAuth(tokenService.getCybrillaPreVerificationAccessToken());
    }

    /**
     * Retries transient connectivity failures (DNS resolution, connection
     * refused, read timeout — all surfaced by Spring as ResourceAccessException)
     * a few times with a short backoff. If the provider is still unreachable
     * after the last attempt, a CybrillaUnavailableException is thrown so callers
     * can degrade gracefully and the API can answer 503 instead of 502.
     * Business errors (4xx/5xx with a response body) are RestClientResponse
     * exceptions, NOT ResourceAccessException, so they pass straight through.
     */
    private <T> T executeWithConnectivityRetry(String operation, Supplier<T> supplier) {
        ResourceAccessException lastError = null;
        for (int attempt = 1; attempt <= CONNECTIVITY_RETRY_MAX_ATTEMPTS; attempt++) {
            try {
                return supplier.get();
            } catch (ResourceAccessException ex) {
                lastError = ex;
                logger.warn(
                        "cybrilla_api connectivity_error operation='{}' attempt='{}/{}' cause='{}'",
                        operation, attempt, CONNECTIVITY_RETRY_MAX_ATTEMPTS, rootCauseMessage(ex)
                );
                if (attempt < CONNECTIVITY_RETRY_MAX_ATTEMPTS) {
                    sleep(CONNECTIVITY_RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw new CybrillaUnavailableException(
                "Unable to reach the external investment platform to " + operation
                        + " (network/DNS unreachable): " + rootCauseMessage(lastError),
                lastError
        );
    }

    private <T> T executeWithTenantTokenRetry(String operation, Supplier<T> supplier) {
        try {
            return executeWithConnectivityRetry(operation, supplier);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenService.invalidateFinprimTenantToken();
                clearScopedTenantBearer();
                try {
                    return executeWithConnectivityRetry(operation, supplier);
                } catch (RestClientResponseException retryEx) {
                    throw apiException(operation, retryEx);
                }
            }
            throw apiException(operation, ex);
        } catch (RestClientException ex) {
            throw apiException(operation, ex);
        } catch (ExternalApiAuthenticationException ex) {
            throw new CybrillaApiException("Unable to authenticate with Fintech Primitives while trying to " + operation, ex);
        }
    }

    private <T> T executeWithPoaTokenRetry(String operation, Supplier<T> supplier) {
        try {
            return executeWithConnectivityRetry(operation, supplier);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenService.invalidateCybrillaPreVerificationToken();
                try {
                    return executeWithConnectivityRetry(operation, supplier);
                } catch (RestClientResponseException retryEx) {
                    throw poaApiException(operation, retryEx);
                }
            }
            throw poaApiException(operation, ex);
        } catch (RestClientException ex) {
            throw poaApiException(operation, ex);
        } catch (ExternalApiAuthenticationException ex) {
            throw new CybrillaApiException("Unable to authenticate with Cybrilla POA while trying to " + operation, ex);
        } catch (IllegalStateException ex) {
            throw new CybrillaApiException("Cybrilla POA configuration error while trying to " + operation + ": " + ex.getMessage(), ex);
        }
    }

    private CybrillaApiException poaApiException(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String detail = StringUtils.hasText(responseBody) ? " response=" + responseBody : "";
        return new CybrillaApiException(
                "Unable to " + operation + " with Cybrilla POA: " + ex.getStatusCode() + detail,
                ex
        );
    }

    private CybrillaApiException poaApiException(String operation, RestClientException ex) {
        return new CybrillaApiException(
                "Unable to " + operation + " with Cybrilla POA: " + rootCauseMessage(ex),
                ex
        );
    }

    private boolean isNotFound(CybrillaApiException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RestClientResponseException responseEx) {
            return HttpStatus.NOT_FOUND.equals(responseEx.getStatusCode());
        }
        return ex.getMessage() != null && ex.getMessage().contains("404");
    }

    private static boolean isFpBankVerificationUnavailable(CybrillaApiException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("not enabled")
                || normalized.contains("not available")
                || normalized.contains("not configured")
                || normalized.contains("unsupported");
    }

    private CybrillaApiException apiException(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String detail = StringUtils.hasText(responseBody) ? " response=" + responseBody : "";
        return new CybrillaApiException(
                "Unable to " + operation + " with Fintech Primitives: " + ex.getStatusCode() + detail,
                ex
        );
    }

    private CybrillaApiException apiException(String operation, RestClientException ex) {
        return new CybrillaApiException(
                "Unable to " + operation + " with Fintech Primitives: " + rootCauseMessage(ex),
                ex
        );
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        return root.getMessage() == null ? throwable.getClass().getSimpleName() : root.getMessage();
    }

    private Map<String, Object> investorProfilePayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "type", "individual");
        put(payload, "tax_status", "resident_individual");
        put(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            put(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        put(payload, "pan", investor.getPan() != null ? investor.getPan().trim().toUpperCase() : null);
        put(payload, "country_of_birth", "IN");
        put(payload, "place_of_birth", investor.getCity());
        put(payload, "nationality_country", "IN");
        put(payload, "use_default_tax_residences", true);
        put(payload, "source_of_wealth", resolveSourceOfWealth(investor));
        put(payload, "income_slab", resolveIncomeSlab(investor));
        put(payload, "pep_details", resolvePepDetails(investor));
        put(payload, "occupation", resolveOccupation(investor));
        put(payload, "gender", resolveGender(investor));
        return payload;
    }

    private Map<String, Object> investorProfileOrderReadyPayload(Investor investor, JsonNode existingProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", investor.getCybrillaInvestorId().trim());
        // Occupation is set on POST /v2/investor_profiles (create) only. FP often omits it on GET
        // even when it is already stored, then rejects PATCH with "already set and cannot be modified".
        if (!hasProfileText(existingProfile, "gender")) {
            put(payload, "gender", resolveGender(investor));
        }
        if (!hasProfileText(existingProfile, "country_of_birth")) {
            put(payload, "country_of_birth", "IN");
        }
        if (!hasProfileText(existingProfile, "place_of_birth")) {
            put(payload, "place_of_birth", investor.getCity());
        }
        if (!hasProfileText(existingProfile, "nationality_country")) {
            put(payload, "nationality_country", "IN");
        }
        if (existingProfile == null || existingProfile.isMissingNode() || !existingProfile.has("use_default_tax_residences")) {
            put(payload, "use_default_tax_residences", true);
        }
        if (!hasProfileText(existingProfile, "source_of_wealth")) {
            put(payload, "source_of_wealth", resolveSourceOfWealth(investor));
        }
        if (!hasProfileText(existingProfile, "income_slab")) {
            put(payload, "income_slab", resolveIncomeSlab(investor));
        }
        if (!hasProfileText(existingProfile, "pep_details")) {
            put(payload, "pep_details", resolvePepDetails(investor));
        }
        // BUG-008: occupation is intentionally NOT included in the order-ready PATCH payload.
        // FP rejects PATCH with "occupation is already set and cannot be modified" when it stores
        // the value but omits it on GET. Occupation is set on POST /v2/investor_profiles (create)
        // only (see createInvestorProfile at :2445). The retry at :2522-2541 stays as defense-in-depth.
        return payload;
    }

    /**
     * Stricter than {@link #hasProfileText}: FP GET may return empty enum objects for unset fields.
     */
    private void assertExistingProfileSupportsOrderSubmission(Investor investor, String profileId, JsonNode existingProfile) {
        if (hasProfileFieldValue(existingProfile, "occupation")) {
            return;
        }
        throw new CybrillaApiException(
                "Fintech Primitives investor profile " + profileId
                        + " is missing occupation, which cannot be added via PATCH after the profile was first created. "
                        + "Assign a fresh sandbox PAN (pattern XXXPX3751X) for investor "
                        + investor.getId()
                        + " and clear cybrilla_investor_id so POST /v2/investor_profiles can create a complete profile."
        );
    }

    private boolean hasProfileFieldValue(JsonNode profile, String field) {
        if (profile == null || profile.isMissingNode() || profile.isNull()) {
            return false;
        }
        JsonNode value = profile.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText());
        }
        if (value.isObject()) {
            JsonNode inner = value.path("value");
            if (!inner.isMissingNode() && !inner.isNull() && inner.isTextual()) {
                return StringUtils.hasText(inner.asText());
            }
            return false;
        }
        return hasProfileText(profile, field);
    }

    private void patchInvestorProfileOrderReady(Map<String, Object> payload) {
        Map<String, Object> attemptPayload = new LinkedHashMap<>(payload);
        while (attemptPayload.size() > 1) {
            try {
                patch("ensure_investor_profile_order_ready", "/v2/investor_profiles", attemptPayload);
                return;
            } catch (RestClientResponseException ex) {
                if (!isBadRequest(ex)) {
                    throw ex;
                }
                String immutableField = extractImmutableProfileField(ex.getResponseBodyAsString());
                if (!StringUtils.hasText(immutableField) || attemptPayload.remove(immutableField) == null) {
                    throw ex;
                }
                logger.info(
                        "cybrilla_workflow operation='ensure_investor_profile_order_ready' status='skip_immutable_field' field='{}'",
                        immutableField);
            }
        }
    }

    private static boolean isBadRequest(RestClientResponseException ex) {
        return ex.getStatusCode().value() == HttpStatus.BAD_REQUEST.value();
    }

    private boolean hasProfileText(JsonNode profile, String field) {
        if (profile == null || profile.isMissingNode() || profile.isNull()) {
            return false;
        }
        JsonNode value = profile.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText());
        }
        // FP may return enum-like objects; treat any non-null node as present to avoid re-PATCHing immutable fields.
        return !value.isMissingNode();
    }

    private String extractImmutableProfileField(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String message = root.path("error").path("message").asText(null);
            if (!StringUtils.hasText(message)) {
                message = root.path("message").asText(null);
            }
            if (StringUtils.hasText(message)) {
                String field = extractImmutableFieldFromMessage(message);
                if (StringUtils.hasText(field)) {
                    return field;
                }
            }
        } catch (IOException ignored) {
            // Fall back to raw body parsing below.
        }
        return extractImmutableFieldFromMessage(responseBody);
    }

    private String extractImmutableFieldFromMessage(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String marker = " is already set and cannot be modified";
        int markerIndex = text.indexOf(marker);
        if (markerIndex <= 0) {
            return null;
        }
        String field = text.substring(0, markerIndex).trim();
        if (field.startsWith("\"") && field.endsWith("\"") && field.length() > 1) {
            field = field.substring(1, field.length() - 1).trim();
        }
        return StringUtils.hasText(field) ? field : null;
    }

    private Map<String, Object> investorProfileUpdatePayload(Investor investor, JsonNode existingProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", investor.getCybrillaInvestorId().trim());
        if (!hasProfileText(existingProfile, "tax_status")) {
            put(payload, "tax_status", "resident_individual");
        }
        if (!hasProfileText(existingProfile, "name")) {
            put(payload, "name", investor.getFullName());
        }
        if (!hasProfileText(existingProfile, "date_of_birth") && investor.getDateOfBirth() != null) {
            put(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        if (!hasProfileText(existingProfile, "country_of_birth")) {
            put(payload, "country_of_birth", "IN");
        }
        if (!hasProfileText(existingProfile, "place_of_birth")) {
            put(payload, "place_of_birth", investor.getCity());
        }
        if (!hasProfileText(existingProfile, "nationality_country")) {
            put(payload, "nationality_country", "IN");
        }
        if (existingProfile == null || existingProfile.isMissingNode() || !existingProfile.has("use_default_tax_residences")) {
            put(payload, "use_default_tax_residences", true);
        }
        if (!hasProfileText(existingProfile, "source_of_wealth")) {
            put(payload, "source_of_wealth", resolveSourceOfWealth(investor));
        }
        if (!hasProfileText(existingProfile, "income_slab")) {
            put(payload, "income_slab", resolveIncomeSlab(investor));
        }
        if (!hasProfileText(existingProfile, "pep_details")) {
            put(payload, "pep_details", resolvePepDetails(investor));
        }
        if (!hasProfileText(existingProfile, "gender")) {
            put(payload, "gender", resolveGender(investor));
        }
        // Occupation is immutable after first FP write — set on POST create only (investorProfilePayload).
        return payload;
    }

    private String resolveGender(Investor investor) {
        String gender = onboardingNoteValue(investor, "gender");
        if (!StringUtils.hasText(gender)) {
            return "female";
        }
        return switch (gender.trim().toLowerCase(Locale.ROOT)) {
            case "m", "male" -> "male";
            case "f", "female" -> "female";
            case "transgender", "t", "other", "others" -> "transgender";
            default -> gender.trim().toLowerCase(Locale.ROOT);
        };
    }

    private String resolveOccupation(Investor investor) {
        String occupation = onboardingNoteValue(investor, "occupation");
        if (!StringUtils.hasText(occupation)) {
            return "service";
        }
        String normalized = occupation.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (normalized) {
            case "business", "professional", "retired", "house_wife", "student",
                    "public_sector_service", "private_sector_service", "government_service",
                    "others", "agriculture", "doctor", "forex_dealer", "service" -> normalized;
            case "salaried", "employed" -> "service";
            case "self_employed", "self-employed" -> "business";
            default -> "service";
        };
    }

    private String resolveIncomeSlab(Investor investor) {
        String income = onboardingNoteValue(investor, "income");
        if (!StringUtils.hasText(income)) {
            return "upto_1lakh";
        }
        String normalized = income.toLowerCase(Locale.ROOT);
        if (normalized.contains("below") || normalized.contains("1 l")) {
            return "upto_1lakh";
        }
        if (normalized.contains("1–5") || normalized.contains("1-5")) {
            return "between_1_to_5_lakhs";
        }
        if (normalized.contains("5–10") || normalized.contains("5-10")) {
            return "between_5_to_10_lakhs";
        }
        if (normalized.contains("10–25") || normalized.contains("10-25")) {
            return "between_10_to_25_lakhs";
        }
        if (normalized.contains("25–50") || normalized.contains("25-50") || normalized.contains("above")) {
            return "above_25_lakhs";
        }
        return "upto_1lakh";
    }

    private String resolveSourceOfWealth(Investor investor) {
        String occupation = onboardingNoteValue(investor, "occupation");
        if (!StringUtils.hasText(occupation)) {
            return "salary";
        }
        String normalized = occupation.toLowerCase(Locale.ROOT);
        if (normalized.contains("business")) {
            return "business";
        }
        if (normalized.contains("self")) {
            return "business";
        }
        return "salary";
    }

    private String resolvePepDetails(Investor investor) {
        String pep = onboardingNoteValue(investor, "pep");
        if ("yes".equalsIgnoreCase(pep) || "true".equalsIgnoreCase(pep)) {
            return "applicable";
        }
        return "not_applicable";
    }

    private String onboardingNoteValue(Investor investor, String key) {
        if (investor == null || !StringUtils.hasText(investor.getOnboardingNotes()) || !StringUtils.hasText(key)) {
            return null;
        }
        for (String part : investor.getOnboardingNotes().split(";")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (key.equalsIgnoreCase(trimmed.substring(0, separator).trim())) {
                return trimmed.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private Map<String, Object> addressPayload(String profileId, Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "line1", investor.getAddressLine1());
        put(payload, "line2", investor.getAddressLine2());
        put(payload, "city", investor.getCity());
        put(payload, "state", investor.getState());
        put(payload, "postal_code", investor.getPostalCode());
        put(payload, "country", "IN");
        put(payload, "nature", "residential");
        return payload;
    }

    private Map<String, Object> emailPayload(String profileId, Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "email", investor.getEmail());
        put(payload, "belongs_to", "self");
        return payload;
    }

    private Map<String, Object> phonePayload(String profileId, Investor investor) {
        PhoneParts phoneParts = PhoneParts.from(investor.getMobileNumber());
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "isd", phoneParts.isd());
        put(payload, "number", phoneParts.number());
        put(payload, "belongs_to", "self");
        return payload;
    }

    private Map<String, Object> bankAccountPayload(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", investor.getCybrillaInvestorId());
        put(payload, "primary_account_holder_name", bankAccount.getAccountHolderName());
        put(payload, "account_number", bankAccount.getAccountNumber());
        put(payload, "type", normalizeBankAccountType(bankAccount.getAccountType()));
        put(payload, "ifsc_code", bankAccount.getIfscCode());
        return payload;
    }

    private Map<String, Object> mfInvestmentAccountPayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "primary_investor", investor.getCybrillaInvestorId());
        put(payload, "holding_pattern", "single");
        return payload;
    }

    private Map<String, Object> kycValidationPayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = investor.getPan() == null ? null : investor.getPan().trim().toUpperCase();
        // POA accepts a combined payload: demographic validation + KYC readiness in one call.
        put(payload, "investor_identifier", pan);
        putPoaValue(payload, "pan", pan);
        putPoaValue(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            putPoaValue(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        return payload;
    }

    private Map<String, Object> combinedOrderPreVerificationPayload(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = new LinkedHashMap<>(kycValidationPayload(investor));
        if (bankAccount != null) {
            Map<String, Object> bankValue = new LinkedHashMap<>();
            put(bankValue, "account_number", bankAccount.getAccountNumber());
            put(bankValue, "ifsc_code", bankAccount.getIfscCode());
            put(bankValue, "account_type", normalizeBankAccountType(bankAccount.getAccountType()));
            payload.put("bank_accounts", List.of(Map.of("value", bankValue)));
        }
        return payload;
    }

    private Map<String, Object> readinessPayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = investor.getPan() == null ? null : investor.getPan().trim().toUpperCase();
        put(payload, "investor_identifier", pan);
        return payload;
    }

    private Map<String, Object> bankAccountPreVerificationPayload(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = investor.getPan() == null ? null : investor.getPan().trim().toUpperCase();
        put(payload, "investor_identifier", pan);
        putPoaValue(payload, "pan", pan);
        putPoaValue(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            putPoaValue(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        Map<String, Object> bankValue = new LinkedHashMap<>();
        put(bankValue, "account_number", bankAccount.getAccountNumber());
        put(bankValue, "ifsc_code", bankAccount.getIfscCode());
        put(bankValue, "account_type", normalizeBankAccountType(bankAccount.getAccountType()));
        payload.put("bank_accounts", List.of(Map.of("value", bankValue)));
        return payload;
    }

    private String normalizeBankAccountType(String accountType) {
        if (!StringUtils.hasText(accountType)) {
            return "savings";
        }
        String normalized = accountType.trim().toLowerCase();
        return switch (normalized) {
            case "savings", "current", "nre_savings", "nro_savings" -> normalized;
            default -> throw new CybrillaApiException(
                    "Unsupported bank account type '" + accountType + "'. Supported values: savings, current, nre_savings, nro_savings"
            );
        };
    }

    private JsonNode firstBankAccountVerification(JsonNode verification) {
        if (verification == null || !verification.has("bank_accounts") || !verification.path("bank_accounts").isArray()
                || verification.path("bank_accounts").isEmpty()) {
            return null;
        }
        return verification.path("bank_accounts").get(0);
    }

    private void putPoaValue(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, Map.of("value", value.trim()));
        }
    }

    private Map<String, Object> mfPurchasePayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : order.getId().toString());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        put(payload, "user_ip", "127.0.0.1");
        put(payload, "gateway", MF_PURCHASE_GATEWAY);
        put(payload, "initiated_via", "web");
        return payload;
    }

    private Map<String, Object> mfPurchasePlanPayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        return mfPurchasePlanBasePayload(order, investor, productScheme, null);
    }

    private Map<String, Object> mfPurchasePlanWithMandatePayload(
            TransactionOrder order,
            Investor investor,
            ProductScheme productScheme,
            int mandateId
    ) {
        Map<String, Object> payload = mfPurchasePlanBasePayload(order, investor, productScheme, mandateId);
        put(payload, "payment_method", "mandate");
        put(payload, "payment_source", mandateId);
        put(payload, "generate_first_installment_now", true);
        return payload;
    }

    private Map<String, Object> mfPurchasePlanBasePayload(
            TransactionOrder order,
            Investor investor,
            ProductScheme productScheme,
            Integer mandateId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : order.getId().toString());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        put(payload, "systematic", true);
        put(payload, "frequency", externalFrequency(order.getSipFrequency()));
        put(payload, "start_date", order.getSipStartDate() == null ? null : order.getSipStartDate().toString());
        put(payload, "number_of_installments", order.getSipInstalments());
        put(payload, "auto_generate_installments", true);
        put(payload, "user_ip", "127.0.0.1");
        put(payload, "gateway", MF_PURCHASE_GATEWAY);
        put(payload, "initiated_via", "web");
        if (mandateId != null && mandateId > 0) {
            put(payload, "payment_method", "mandate");
            put(payload, "payment_source", mandateId);
            put(payload, "generate_first_installment_now", true);
        }
        return payload;
    }

    private Map<String, Object> redemptionPayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : "redemption-" + order.getId());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        put(payload, "units", order.getUnits());
        put(payload, "gateway", MF_PURCHASE_GATEWAY);
        return payload;
    }

    private String schemeIdentifier(ProductScheme productScheme) {
        if (productScheme == null) {
            throw new CybrillaApiException("Product scheme is required before order creation");
        }
        String scheme = defaultText(productScheme.getExternalIsin(), productScheme.getExternalSchemeCode());
        if (!StringUtils.hasText(scheme)) {
            throw new CybrillaApiException("Product scheme must have an external ISIN or scheme code before order creation");
        }
        return scheme;
    }

    private String externalFrequency(String sipFrequency) {
        if (!StringUtils.hasText(sipFrequency)) {
            return null;
        }
        return switch (sipFrequency.trim().toUpperCase()) {
            case "MONTHLY" -> "monthly";
            case "QUARTERLY" -> "quarterly";
            default -> sipFrequency.trim().toLowerCase();
        };
    }

    private String idempotencyKey(String prefix, UUID id) {
        return id == null ? null : prefix + "-" + id;
    }

    private ProductScheme toProductScheme(JsonNode schemeNode, String requestSnapshotJson) {
        // FP's fund scheme master is keyed by ISIN (GET /api/oms/fund_schemes/{isin}),
        // so ISIN is the most stable unique key. scheme_code can collide across plans.
        String isin = firstText(schemeNode, "isin", "isin_code");
        String externalCode = firstText(schemeNode, "isin", "scheme_code", "id", "fund_scheme_id", "code");
        if (!StringUtils.hasText(externalCode)) {
            externalCode = isin;
        }
        if (!StringUtils.hasText(externalCode)) {
            throw new CybrillaApiException("Fund scheme response did not include an isin, scheme_code, id, or fund_scheme_id");
        }

        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName(defaultText(
                firstText(schemeNode, "name", "scheme_name", "fund_scheme_name", "display_name"),
                defaultText(
                        nestedText(schemeNode, "sif_scheme", "name"),
                        defaultText(nestedText(schemeNode, "mf_scheme", "name"), externalCode)
                )
        ));
        ProductCategory category = resolveProductCategory(schemeNode);
        scheme.setAmcName(defaultText(
                nestedText(schemeNode, category == ProductCategory.SIF ? "sif_fund" : "mf_fund", "name"),
                resolveAmcName(schemeNode)
        ));
        scheme.setCategory(category);
        scheme.setExternalSchemeCode(externalCode);
        scheme.setExternalIsin(isin);
        scheme.setProductType(category == ProductCategory.SIF ? "SIF" : "MUTUAL_FUND");
        scheme.setActive(resolveActive(schemeNode));
        scheme.setExternalFetchRequestJson(requestSnapshotJson);
        scheme.setMetadataJson(buildSchemeMetadata(schemeNode));
        return scheme;
    }

    /**
     * Keeps the full FP payload but normalizes a couple of keys the distributor UI
     * reads (category / min purchase / trade-eligibility flags) so fetched schemes
     * render with the right SEBI category and minimums instead of falling back to
     * "Equity / Rs 100".
     */
    private String buildSchemeMetadata(JsonNode schemeNode) {
        if (!(schemeNode instanceof ObjectNode source)) {
            return schemeNode.toString();
        }
        ObjectNode metadata = source.deepCopy();
        String fundCategory = firstText(schemeNode, "fund_category", "category", "scheme_category");
        if (StringUtils.hasText(fundCategory) && !metadata.hasNonNull("category")) {
            metadata.put("category", fundCategory);
        }
        return metadata.toString();
    }

    private String nestedText(JsonNode node, String objectName, String fieldName) {
        JsonNode value = node.path(objectName).path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private JsonNode extractSchemeArray(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new CybrillaApiException("Fund scheme response was empty");
        }
        if (response.isArray()) {
            return response;
        }
        for (String fieldName : List.of("fund_schemes", "sif_scheme_plans", "mf_scheme_plans", "data", "content", "items", "results")) {
            JsonNode candidate = response.path(fieldName);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        throw new CybrillaApiException("Fund scheme response did not include an array of schemes");
    }

    private boolean isLastPage(JsonNode response) {
        if (response == null || response.isArray()) {
            return false;
        }
        if (response.path("last").asBoolean(false)) {
            return true;
        }
        int page = response.path("page").asInt(-1);
        int totalPages = firstInt(response, "total_pages", "totalPages", "pages");
        return page >= 0 && totalPages > 0 && page + 1 >= totalPages;
    }

    private ProductCategory resolveProductCategory(JsonNode schemeNode) {
        String objectType = firstText(schemeNode, "object");
        if (StringUtils.hasText(objectType)) {
            String normalized = objectType.trim().toLowerCase();
            if (normalized.contains("sif")) {
                return ProductCategory.SIF;
            }
        }
        String productType = firstText(schemeNode, "product_type", "instrument_type", "asset_class");
        if (StringUtils.hasText(productType) && productType.trim().toUpperCase().contains("SIF")) {
            return ProductCategory.SIF;
        }
        return ProductCategory.MF;
    }

    private boolean resolveActive(JsonNode schemeNode) {
        for (String fieldName : List.of("active", "is_active", "enabled")) {
            JsonNode value = schemeNode.path(fieldName);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return true;
    }

    private String resolveAmcName(JsonNode schemeNode) {
        String directName = firstText(schemeNode, "amc_name", "fund_house", "fund_house_name", "amc");
        if (StringUtils.hasText(directName)) {
            return directName;
        }
        JsonNode amcNode = schemeNode.path("amc");
        if (amcNode.isObject()) {
            String nestedName = firstText(amcNode, "name", "amc_name");
            if (StringUtils.hasText(nestedName)) {
                return nestedName;
            }
        }
        String amcId = firstText(schemeNode, "amc_id");
        return StringUtils.hasText(amcId) ? "AMC " + amcId : "Unknown AMC";
    }

    private int firstInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual() && value.asText().matches("\\d+")) {
                return Integer.parseInt(value.asText());
            }
        }
        return -1;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            logger.warn("external_snapshot status='serialize_failed' reason='{}'", ex.getMessage());
            return null;
        }
    }

    private String extractId(JsonNode response, String objectName) {
        if (response != null && response.hasNonNull("id")) {
            return response.get("id").asText();
        }
        throw new CybrillaApiException("Fintech Primitives " + objectName + " response did not include id");
    }

    private Integer extractOldId(JsonNode response) {
        if (response != null && response.has("old_id") && !response.get("old_id").isNull()) {
            return response.get("old_id").asInt();
        }
        return null;
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value instanceof String stringValue) {
            if (StringUtils.hasText(stringValue)) {
                payload.put(key, stringValue.trim());
            }
            return;
        }
        if (value != null) {
            payload.put(key, value);
        }
    }

    private record PhoneParts(String isd, String number) {
        static PhoneParts from(String rawPhone) {
            String digits = rawPhone == null ? "" : rawPhone.replaceAll("[^0-9]", "");
            if (digits.startsWith("91") && digits.length() > 10) {
                return new PhoneParts("91", digits.substring(2));
            }
            return new PhoneParts("91", digits);
        }
    }
}

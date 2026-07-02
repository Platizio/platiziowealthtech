package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.InvestorNominee;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.ApprovalDetailResponse;
import com.platizio.wealthtech.dto.ApprovalRequest;
import com.platizio.wealthtech.dto.ApprovalSummaryResponse;
import com.platizio.wealthtech.dto.ContactDeclarationRequest;
import com.platizio.wealthtech.dto.ContactVerificationStatus;
import com.platizio.wealthtech.dto.HoldingResponse;
import com.platizio.wealthtech.dto.InvestorAuthResponse;
import com.platizio.wealthtech.dto.InvestorDashboardResponse;
import com.platizio.wealthtech.dto.OnboardingAttestRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.dto.OtpVerifyCodeRequest;
import com.platizio.wealthtech.dto.WithdrawalRequest;
import com.platizio.wealthtech.dto.AadhaarVerificationResponse;
import com.platizio.wealthtech.dto.EsignStartRequest;
import com.platizio.wealthtech.dto.EsignVerificationResponse;
import com.platizio.wealthtech.dto.IdentityDocumentCreateRequest;
import com.platizio.wealthtech.dto.InvestorExternalKycResponse;
import com.platizio.wealthtech.dto.InvestorKycRequestCreateRequest;
import com.platizio.wealthtech.dto.KycFlowAdvanceResponse;
import com.platizio.wealthtech.dto.KycFlowStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorOrderRequest;
import com.platizio.wealthtech.dto.KycReadinessDecision;
import com.platizio.wealthtech.dto.NomineeRequest;
import com.platizio.wealthtech.dto.NomineeResponse;
import org.springframework.data.domain.Page;
import com.platizio.wealthtech.security.InvestorAuthPrincipal;
import com.platizio.wealthtech.service.ConsentRecordService;
import com.platizio.wealthtech.service.HoldingsService;
import com.platizio.wealthtech.service.InvestorActionService;
import com.platizio.wealthtech.service.InvestorAuthService;
import com.platizio.wealthtech.service.InvestorContactVerificationService;
import com.platizio.wealthtech.service.InvestorKycService;
import com.platizio.wealthtech.service.OnboardingSubmissionService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.NomineeService;
import com.platizio.wealthtech.service.OrderService;
import com.platizio.wealthtech.service.PortfolioService;
import com.platizio.wealthtech.service.ProductService;
import com.platizio.wealthtech.service.TransactionApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated investor's own portal (role {@code ROLE_INVESTOR}). Every
 * endpoint resolves the acting account from the {@link InvestorAuthPrincipal};
 * onboarding-review and contact-self endpoints additionally require the account to
 * be linked to a distributor-created investor profile (by PAN).
 */
@RestController
@RequestMapping("/api/v1/investor")
@Tag(name = "Investor Portal", description = "Self-service endpoints for the authenticated investor")
public class InvestorPortalController {

    static final String CONSENT_ONBOARDING_ATTESTATION = "onboarding_attestation";

    private final InvestorAuthService investorAuthService;
    private final OnboardingSubmissionService onboardingSubmissionService;
    private final InvestorContactVerificationService contactVerificationService;
    private final ConsentRecordService consentRecordService;
    private final TransactionApprovalService transactionApprovalService;
    private final InvestorActionService investorActionService;
    private final OrderService orderService;
    private final PortfolioService portfolioService;
    private final HoldingsService holdingsService;
    private final InvestorKycService investorKycService;
    private final ProductService productService;
    private final InvestorService investorService;
    private final NomineeService nomineeService;

    public InvestorPortalController(
            InvestorAuthService investorAuthService,
            OnboardingSubmissionService onboardingSubmissionService,
            InvestorContactVerificationService contactVerificationService,
            ConsentRecordService consentRecordService,
            TransactionApprovalService transactionApprovalService,
            InvestorActionService investorActionService,
            OrderService orderService,
            PortfolioService portfolioService,
            HoldingsService holdingsService,
            InvestorKycService investorKycService,
            ProductService productService,
            InvestorService investorService,
            NomineeService nomineeService) {
        this.investorKycService = investorKycService;
        this.productService = productService;
        this.investorService = investorService;
        this.investorAuthService = investorAuthService;
        this.onboardingSubmissionService = onboardingSubmissionService;
        this.contactVerificationService = contactVerificationService;
        this.consentRecordService = consentRecordService;
        this.transactionApprovalService = transactionApprovalService;
        this.investorActionService = investorActionService;
        this.orderService = orderService;
        this.portfolioService = portfolioService;
        this.holdingsService = holdingsService;
        this.nomineeService = nomineeService;
    }

    @Operation(summary = "Current investor session", description = "Returns the authenticated investor's account view.")
    @GetMapping("/me")
    public InvestorAuthResponse me(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return InvestorAuthResponse.from(account, investorAuthService.investorKycStatusFor(account),
                investorAuthService.investorDistributorNameFor(account));
    }

    // ── Onboarding review + attestation ──────────────────────────────────────

    @Operation(summary = "Review the onboarding draft awaiting my approval",
            description = "Returns the latest frozen revision the distributor sent for review, or hasDraft:false when none.")
    @GetMapping("/onboarding/review")
    public Map<String, Object> review(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        UUID investorId = account.getInvestorId();
        if (investorId == null) {
            return Map.of("hasDraft", false);
        }
        Optional<OnboardingSubmission> latest = onboardingSubmissionService.latestRevisionForInvestor(investorId);
        if (latest.isEmpty()) {
            return Map.of("hasDraft", false);
        }
        return reviewBody(latest.get());
    }

    @Operation(summary = "Attest the onboarding draft",
            description = "Records the investor's approval of the exact revision (by hash) and an immutable consent record.")
    @PostMapping("/onboarding/attest")
    public Map<String, Object> attest(
            @Valid @RequestBody OnboardingAttestRequest request,
            HttpServletRequest httpRequest,
            Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        UUID investorId = account.getInvestorId();
        if (investorId == null) {
            throw new IllegalArgumentException("No onboarding draft is linked to your account yet.");
        }
        String ip = clientIp(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        OnboardingSubmission attested = onboardingSubmissionService.attest(
                investorId, account.getId(), request.revisionHash(), ip, ua);
        consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, account.getId(), CONSENT_ONBOARDING_ATTESTATION,
                "rev-" + attested.getRevisionNo(), attested.getContentSha256(), ip, ua);
        return reviewBody(attested);
    }

    // ── Contact verification (self) ──────────────────────────────────────────

    @Operation(summary = "Send my email verification OTP")
    @PostMapping("/contact/email/otp/request")
    public ContactVerificationStatus requestEmailOtp(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.requestEmailOtpAsInvestor(requireInvestorId(account), account.getId());
    }

    @Operation(summary = "Verify my email OTP")
    @PostMapping("/contact/email/otp/verify")
    public ContactVerificationStatus verifyEmailOtp(
            @Valid @RequestBody OtpVerifyCodeRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.verifyEmailOtpAsInvestor(
                requireInvestorId(account), account.getId(), request.code());
    }

    @Operation(summary = "Send my mobile verification OTP")
    @PostMapping("/contact/mobile/otp/request")
    public ContactVerificationStatus requestMobileOtp(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.requestMobileOtpAsInvestor(requireInvestorId(account), account.getId());
    }

    @Operation(summary = "Verify my mobile OTP")
    @PostMapping("/contact/mobile/otp/verify")
    public ContactVerificationStatus verifyMobileOtp(
            @Valid @RequestBody OtpVerifyCodeRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.verifyMobileOtpAsInvestor(
                requireInvestorId(account), account.getId(), request.code());
    }

    @Operation(summary = "Self-declare a contact belongs to me")
    @PostMapping("/contact/declare")
    public ContactVerificationStatus declareContact(
            @Valid @RequestBody ContactDeclarationRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.declareContactAsInvestor(
                requireInvestorId(account), account.getId(), request.channel(), request.belongsTo());
    }

    @Operation(summary = "My contact verification status")
    @GetMapping("/contact/status")
    public ContactVerificationStatus contactStatus(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return contactVerificationService.getStatusAsInvestor(requireInvestorId(account));
    }

    // ── KYC self-service (A1 plumbing · A2 PAN verify · A3 DigiLocker) ────────
    // All endpoints resolve the investor from the session and delegate to the
    // InvestorKycService *AsInvestor variants (no distributor-ownership check).

    @Operation(summary = "My KYC flow status (A3)",
            description = "Current KYC readiness + next action for the authenticated investor.")
    @GetMapping("/kyc/status")
    public KycFlowStatusResponse kycStatus(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.getKycFlowStatusAsInvestor(requireInvestorId(account));
    }

    @Operation(summary = "Advance my KYC flow",
            description = "Runs the next KYC step (pre-verify → KYC request → Aadhaar → eSign).")
    @PostMapping("/kyc/advance")
    public KycFlowAdvanceResponse advanceKyc(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.advanceKycFlowAsInvestor(requireInvestorId(account));
    }

    @Operation(summary = "Verify my PAN (A2)",
            description = "Runs the Cybrilla KYC compliance check against my PAN and persists the result.")
    @PostMapping("/kyc/pan-verify")
    public InvestorExternalKycResponse verifyPan(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.runKycComplianceCheckAsInvestor(requireInvestorId(account), true);
    }

    @Operation(summary = "Evaluate my KYC readiness (POA pre-verification)",
            description = "Runs the readiness check and returns the next action derived from readiness.code "
                    + "(proceed / submit new KYC / modify KYC / wait / blocked / retry). Investor-only.")
    @PostMapping("/kyc/readiness")
    public KycReadinessDecision evaluateKycReadiness(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.evaluateReadinessAsInvestor(requireInvestorId(account));
    }

    // Sandbox/dev only — controlled by app.kyc.sandbox-simulation-enabled (false in prod).
    @org.springframework.beans.factory.annotation.Value("${app.kyc.sandbox-simulation-enabled:false}")
    private boolean kycSandboxSimulationEnabled;

    @Operation(summary = "Simulate my KYC completion (sandbox only)",
            description = "Sandbox/dev only: drives my KYC request to 'successful' via the FP simulate API so "
                    + "DigiLocker (Aadhaar) + eSign complete without a real DigiLocker session. 403 in production.")
    @PostMapping("/kyc/simulate")
    public InvestorExternalKycResponse simulateKyc(Authentication auth) {
        if (!kycSandboxSimulationEnabled) {
            throw new AccessDeniedException("KYC simulation is not available in this environment.");
        }
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.simulateKycRequestAsInvestor(requireInvestorId(account), "successful");
    }

    @Operation(summary = "Refresh my external KYC status")
    @PostMapping("/kyc/refresh")
    public InvestorExternalKycResponse refreshKyc(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.syncInvestorExternalKycStatusAsInvestor(requireInvestorId(account));
    }

    @Operation(summary = "Create a KYC request (A3)")
    @PostMapping("/kyc/requests")
    public InvestorExternalKycResponse createKycRequest(
            @RequestBody(required = false) InvestorKycRequestCreateRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.createKycRequestAsInvestor(requireInvestorId(account), request);
    }

    @Operation(summary = "Start DigiLocker identity verification (A3)",
            description = "Creates an identity document and returns the DigiLocker redirect details.")
    @PostMapping("/kyc/identity-documents")
    public AadhaarVerificationResponse startDigiLocker(
            @RequestBody(required = false) IdentityDocumentCreateRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.createIdentityDocumentAsInvestor(requireInvestorId(account), request);
    }

    @Operation(summary = "Fetch my DigiLocker documents after callback (A3)")
    @PostMapping("/kyc/identity-documents/refresh")
    public AadhaarVerificationResponse refreshDigiLocker(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.refreshIdentityDocumentForInvestorAsInvestor(requireInvestorId(account));
    }

    @Operation(summary = "Start eSign (A3)",
            description = "Creates an eSign request for my KYC and returns the eSign redirect details.")
    @PostMapping("/kyc/esign/start")
    public EsignVerificationResponse startEsign(
            @RequestBody(required = false) EsignStartRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.createEsignAsInvestor(requireInvestorId(account), request);
    }

    @Operation(summary = "Refresh my eSign status after callback (A3)")
    @PostMapping("/kyc/esign/refresh")
    public EsignVerificationResponse refreshEsign(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.refreshEsignAsInvestor(requireInvestorId(account));
    }

    @Operation(summary = "List my DigiLocker identity documents (A3)")
    @GetMapping("/kyc/identity-documents")
    public JsonNode listDigiLockerDocuments(
            @RequestParam(required = false) String kycRequestId,
            @RequestParam(required = false) String fetchStatus,
            Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorKycService.listIdentityDocumentsAsInvestor(
                requireInvestorId(account), kycRequestId, fetchStatus);
    }

    // ── Profile authoring + profile-change approvals ─────────────────────────
    // Investor↔distributor linking, M3 profile authoring and M4 profile-change
    // approvals are served by the team's canonical InvestorLinkController +
    // InvestorLinkRequestService + ProfileChangeApprovalService (challenge-id
    // centric, /api/v1/investor/link/** and /profile-changes/**), so the portal
    // no longer duplicates them here.

    // ── Bank account (investor-self) ──────────────────────────────────────────

    @Operation(summary = "Add my bank account",
            description = "Adds and verifies a bank account so the investor becomes order-ready.")
    @PostMapping("/bank-accounts")
    public InvestorBankAccount addBankAccount(@Valid @RequestBody InvestorBankRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorService.addBankAccountAsInvestor(requireInvestorId(account), request);
    }

    @Operation(summary = "My bank accounts")
    @GetMapping("/bank-accounts")
    public List<InvestorBankAccount> myBankAccounts(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return investorService.listBankAccountsAsInvestor(requireInvestorId(account));
    }

    // ── Nominations (investor-self · REQUIREMENT #4) ──────────────────────────

    @Operation(summary = "My nominees + opt-out status",
            description = "Lists the investor's nominees and whether they have explicitly opted out of nominating.")
    @GetMapping("/nominations")
    public Map<String, Object> listNominations(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        UUID investorId = requireInvestorId(account);
        List<NomineeResponse> nominees = nomineeService.listNomineesAsInvestor(investorId).stream()
                .map(NomineeResponse::from)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nominees", nominees);
        out.put("optedOut", nomineeService.isOptedOutAsInvestor(investorId));
        return out;
    }

    @Operation(summary = "Add a nominee",
            description = "Adds a nominee to my profile. Allocation percentages across nominees cannot exceed 100%.")
    @PostMapping("/nominations")
    public NomineeResponse addNomination(@Valid @RequestBody NomineeRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        InvestorNominee saved = nomineeService.addNomineeAsInvestor(requireInvestorId(account), request);
        return NomineeResponse.from(saved);
    }

    @Operation(summary = "Opt out of nomination",
            description = "Records an explicit decision not to nominate anyone, with an immutable consent record.")
    @PostMapping("/nominations/opt-out")
    public Map<String, Object> optOutOfNomination(Authentication auth, HttpServletRequest httpRequest) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        nomineeService.optOutAsInvestor(
                requireInvestorId(account), account.getId(),
                clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return Map.of("optedOut", Boolean.TRUE);
    }

    // ── Buy funds (investor-self order placement) ─────────────────────────────

    @Operation(summary = "Browse buyable mutual-fund schemes",
            description = "Live POA catalogue (cybrillapoa) the investor can invest in.")
    @GetMapping("/schemes")
    public Page<ProductScheme> browseSchemes(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        investorAuthService.requireAccount(accountId(auth));
        return productService.syncAvailableFundsFromCybrilla(query, Boolean.TRUE, page, size);
    }

    @Operation(summary = "Place a fund order (buy)",
            description = "Investor-self order placement; gated on KYC COMPLETED + verified bank. "
                    + "Returns the order with its investor-action (payment) URL.")
    @PostMapping("/orders")
    public TransactionOrder placeOrder(@Valid @RequestBody InvestorOrderRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return orderService.createOrderAsInvestor(requireInvestorId(account), request);
    }

    // ── Approval Center (transaction 2FA) ────────────────────────────────────

    @Operation(summary = "List my pending transaction approvals",
            description = "All live (PENDING | CHALLENGE_SENT | APPROVED) 2FA challenges for my account. Never returns the OTP.")
    @GetMapping("/approvals")
    public List<ApprovalSummaryResponse> listApprovals(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return transactionApprovalService.listPendingForInvestor(account.getId()).stream()
                .map(ApprovalSummaryResponse::from)
                .toList();
    }

    @Operation(summary = "Review one approval",
            description = "Returns the frozen snapshot, rendered consent text, masked OTP destination and status. 404/403 if not my challenge.")
    @GetMapping("/approvals/{challengeId}")
    public ApprovalDetailResponse getApproval(@PathVariable UUID challengeId, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        TransactionApprovalChallenge challenge = requireOwnedChallenge(challengeId, account);
        return ApprovalDetailResponse.from(challenge);
    }

    @Operation(summary = "Send my approval OTP",
            description = "Emails the one-time passcode bound to this challenge (PENDING → CHALLENGE_SENT). Never returns the code.")
    @PostMapping("/approvals/{challengeId}/otp/request")
    public OtpRequestResponse requestApprovalOtp(@PathVariable UUID challengeId, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        requireOwnedChallenge(challengeId, account);
        return transactionApprovalService.requestApprovalOtp(challengeId, account.getId(), false);
    }

    @Operation(summary = "Approve a transaction with consent + OTP",
            description = "Verifies consent + the OTP, then drives the gated provider submit for the challenge's type "
                    + "(PURCHASE → payment, SIP → mandate, REDEMPTION → real redemption).")
    @PostMapping("/approvals/{challengeId}/approve")
    public Map<String, Object> approve(
            @PathVariable UUID challengeId,
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest,
            Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        TransactionApprovalChallenge challenge = requireOwnedChallenge(challengeId, account);

        String ip = clientIp(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        String sessionId = httpRequest.getRequestedSessionId();

        // Verifies ownership + consent + OTP, records consent, → APPROVED.
        transactionApprovalService.approve(
                challengeId, account.getId(), request.code(), request.consentAccepted(), ip, ua, sessionId);

        // Drive the gated provider submit for this challenge's type. The 2FA hard gate
        // (assertApprovedAndConsume) lives deep inside each of these, so the provider
        // write only fires now that an APPROVED challenge exists.
        UUID transactionId = challenge.getTransactionId();
        TransactionType type = challenge.getTransactionType();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("challengeId", challengeId);
        result.put("transactionType", type == null ? null : type.name());

        if (type == TransactionType.REDEMPTION) {
            RedemptionRecord submitted = orderService.submitRedemptionToProvider(transactionId);
            result.put("nextAction", "SUBMITTED");
            result.put("redemptionId", submitted.getId());
            result.put("status", submitted.getRedemptionStatus().name());
            result.put("message", "Your withdrawal has been submitted to the provider.");
        } else if (type == TransactionType.SIP) {
            InvestorActionService.InvestorActionPage page =
                    investorActionService.startSipMandateForOrder(transactionId);
            result.put("nextAction", "MANDATE_URL");
            result.put("orderId", transactionId);
            result.put("redirectUrl", page.paymentRedirectUrl());
            result.put("message", page.message());
        } else {
            InvestorActionService.InvestorActionPage page =
                    investorActionService.confirmPurchaseForOrder(transactionId);
            result.put("nextAction", "PAYMENT_REDIRECT");
            result.put("orderId", transactionId);
            result.put("redirectUrl", page.paymentRedirectUrl());
            result.put("message", page.message());
        }
        return result;
    }

    // ── Holdings (withdrawal disclosures) ────────────────────────────────────

    @Operation(summary = "My holdings",
            description = "Investor-scoped holdings with valuation and a dataQuality flag (STALE/UNAVAILABLE) when NAV/units are missing.")
    @GetMapping("/holdings")
    public List<HoldingResponse> holdings(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        return portfolioService.getInvestorHoldings(requireInvestorId(account));
    }

    // ── Dashboard (Phase-3 rich holdings) ────────────────────────────────────

    @Operation(summary = "My holdings dashboard",
            description = "Per-holding net units, latest NAV, invested cost basis, current value, absolute/percent/"
                    + "1-day returns and money-weighted XIRR, plus portfolio totals. Returns null (never zero) for "
                    + "valuation/returns when NAV, units or a previous-day NAV are unavailable, with a dataQuality flag.")
    @GetMapping("/dashboard")
    public InvestorDashboardResponse dashboard(Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        // Degrade gracefully for an account not yet linked to an investor profile:
        // getDashboard(null) returns an empty dashboard (no holdings) rather than a 400,
        // so the dashboard is always a safe landing page after login/signup.
        return holdingsService.getDashboard(account.getInvestorId());
    }

    // ── Withdrawals ──────────────────────────────────────────────────────────

    @Operation(summary = "Request a withdrawal via my distributor (no 2FA)",
            description = "Creates a redemption draft only and notifies the distributor. No 2FA, no provider call.")
    @PostMapping("/withdrawals/request-to-distributor")
    public Map<String, Object> requestWithdrawalToDistributor(
            @Valid @RequestBody WithdrawalRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        UUID orderId = resolveWithdrawalOrderId(request, account);
        RedemptionRecord draft = orderService.createRedemptionDraft(
                orderId, account.getId(), request.mode(), request.value(), isFullRedemption(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redemptionId", draft.getId());
        result.put("status", draft.getRedemptionStatus().name());
        result.put("mode", "REQUEST_TO_DISTRIBUTOR");
        result.put("message", "Your withdrawal request was sent to your distributor.");
        return result;
    }

    @Operation(summary = "Self-service withdrawal (2FA)",
            description = "Creates a redemption draft, then a REDEMPTION approval challenge. Returns {challengeId} to approve "
                    + "via /approvals — on approval a REAL redemption is submitted (locked decision #2).")
    @PostMapping("/withdrawals/self")
    public Map<String, Object> requestWithdrawalSelf(
            @Valid @RequestBody WithdrawalRequest request, Authentication auth) {
        InvestorAccount account = investorAuthService.requireAccount(accountId(auth));
        UUID orderId = resolveWithdrawalOrderId(request, account);
        RedemptionRecord draft = orderService.createRedemptionDraft(
                orderId, account.getId(), request.mode(), request.value(), isFullRedemption(request));
        TransactionApprovalChallenge challenge = transactionApprovalService.createChallenge(
                draft.getId(), TransactionType.REDEMPTION, account.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redemptionId", draft.getId());
        result.put("challengeId", challenge.getId());
        result.put("status", challenge.getStatus().name());
        result.put("mode", "SELF");
        result.put("message", "Approve this withdrawal in your Approval Center with a one-time passcode to submit it.");
        return result;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, Object> reviewBody(OnboardingSubmission submission) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hasDraft", true);
        body.put("revisionNo", submission.getRevisionNo());
        body.put("status", submission.getStatus().name());
        body.put("payloadJson", submission.getPayloadJson());
        body.put("contentSha256", submission.getContentSha256());
        body.put("submittedAt", submission.getSubmittedAt());
        return body;
    }

    private UUID requireInvestorId(InvestorAccount account) {
        UUID investorId = account.getInvestorId();
        if (investorId == null) {
            throw new IllegalArgumentException("Your account is not linked to an investor profile yet.");
        }
        return investorId;
    }

    /**
     * Loads a challenge and enforces it belongs to this account (404 when missing,
     * 403 when it is another investor's). This is the per-resource ownership check on
     * top of the role gate; it keeps one investor from reading or acting on another's
     * approvals via a guessed id.
     */
    private TransactionApprovalChallenge requireOwnedChallenge(UUID challengeId, InvestorAccount account) {
        TransactionApprovalChallenge challenge =
                transactionApprovalService.getChallenge(challengeId)
                        .orElseThrow(() -> new EntityNotFoundException("Approval challenge not found."));
        if (!account.getId().equals(challenge.getInvestorAccountId())) {
            throw new AccessDeniedException("This approval does not belong to your account.");
        }
        return challenge;
    }

    /** A request is a full redemption only when it explicitly sets {@code fullRedemption=true}. */
    private boolean isFullRedemption(WithdrawalRequest request) {
        return Boolean.TRUE.equals(request.fullRedemption());
    }

    /** Resolve the holding order id from {orderId | folio}. */
    private UUID resolveWithdrawalOrderId(WithdrawalRequest request, InvestorAccount account) {
        if (request.orderId() != null) {
            return request.orderId();
        }
        if (request.folio() != null && !request.folio().isBlank()) {
            return portfolioService.getInvestorHoldings(requireInvestorId(account)).stream()
                    .filter(h -> request.folio().equals(h.folio()))
                    .map(HoldingResponse::orderId)
                    .findFirst()
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No holding matches the supplied folio."));
        }
        throw new IllegalArgumentException("Either orderId or folio is required to withdraw.");
    }

    private UUID accountId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof InvestorAuthPrincipal principal)) {
            throw new AccessDeniedException("Authenticated investor principal is required");
        }
        return principal.getInvestorAccountId();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

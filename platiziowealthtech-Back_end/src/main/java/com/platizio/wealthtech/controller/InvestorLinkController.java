package com.platizio.wealthtech.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.dto.ApprovalRequest;
import com.platizio.wealthtech.dto.InvestorLinkReviewResponse;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.dto.ProfileChangeApprovalSummaryResponse;
import com.platizio.wealthtech.dto.ProfileChangeRejectRequest;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorLinkRequestRepository;
import com.platizio.wealthtech.security.InvestorAuthPrincipal;
import com.platizio.wealthtech.service.ConsentRecordService;
import com.platizio.wealthtech.service.InvestorAccountOwnershipGuard;
import com.platizio.wealthtech.service.InvestorAuthService;
import com.platizio.wealthtech.service.InvestorLinkRequestService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.OnboardingSubmissionService;
import com.platizio.wealthtech.service.ProfileChangeApprovalService;
import com.platizio.wealthtech.validation.PanFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The investor-side of the email-approval link loop (investor.md §3, §6.2; R3/R5/R7).
 *
 * <p>Lives under the investor-JWT-secured base path {@code /api/v1/investor} (role
 * {@code ROLE_INVESTOR}, the same gate as {@link InvestorPortalController}); the acting
 * account is resolved from the {@link InvestorAuthPrincipal} on the JWT — NEVER from a
 * request body — exactly as the rest of the investor portal does.
 *
 * <p>The opaque token from the email is the resource address. Every endpoint proves the
 * authenticated investor owns that link with the SAME rule {@code approveByToken} uses:
 * {@link InvestorAccountOwnershipGuard#assertOwns} (account.investorId == request.investorId)
 * PLUS a PAN match, so a caller who merely knows a token cannot review/approve/reject a link
 * minted for a DIFFERENT investor (IDOR — SEC-1/SEC-2). 404 when the token is unknown,
 * 403 when the authenticated investor does not own it.
 */
@RestController
@RequestMapping("/api/v1/investor")
@Tag(name = "Investor Link Approval", description = "Investor review/approve/reject of the distributor email-approval link")
public class InvestorLinkController {

    private static final ObjectMapper LINK_REVIEW_MAPPER = new ObjectMapper();

    /** The live (non-terminal) profile-change states surfaced on the Approvals page. */
    private static final java.util.Set<com.platizio.wealthtech.domain.ProfileChangeApprovalStatus>
            LIVE_PROFILE_STATUSES = java.util.EnumSet.of(
                    com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.PENDING,
                    com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CHALLENGE_SENT,
                    com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.APPROVED);

    private final InvestorAuthService investorAuthService;
    private final InvestorLinkRequestService linkRequestService;
    private final InvestorLinkRequestRepository linkRequestRepository;
    private final OnboardingSubmissionService onboardingSubmissionService;
    private final InvestorAccountOwnershipGuard ownershipGuard;
    private final DistributorRepository distributorRepository;
    private final InvestorService investorService;
    private final ProfileChangeApprovalService profileChangeApprovalService;

    public InvestorLinkController(
            InvestorAuthService investorAuthService,
            InvestorLinkRequestService linkRequestService,
            InvestorLinkRequestRepository linkRequestRepository,
            OnboardingSubmissionService onboardingSubmissionService,
            InvestorAccountOwnershipGuard ownershipGuard,
            DistributorRepository distributorRepository,
            InvestorService investorService,
            ProfileChangeApprovalService profileChangeApprovalService) {
        this.investorAuthService = investorAuthService;
        this.linkRequestService = linkRequestService;
        this.linkRequestRepository = linkRequestRepository;
        this.onboardingSubmissionService = onboardingSubmissionService;
        this.ownershipGuard = ownershipGuard;
        this.distributorRepository = distributorRepository;
        this.investorService = investorService;
        this.profileChangeApprovalService = profileChangeApprovalService;
    }

    @Operation(summary = "Public review of the onboarding link",
            description = "Loads the distributor-entered details by email-link token before the investor signs up/logs in.")
    @GetMapping("/link/review")
    public Map<String, Object> publicReview(@RequestParam String token) {
        return linkRequestService.reviewByToken(token);
    }

    @Operation(summary = "Public approval of the onboarding link",
            description = "Approves the token-addressed link before signup, then links distributor_id by PAN.")
    @PostMapping("/link/approve")
    public Map<String, Object> publicApprove(
            @Valid @RequestBody PublicLinkApprovalRequest request) {
        return linkRequestService.approvePublicByToken(
                request.token(), Boolean.TRUE.equals(request.consentAccepted()));
    }

    @Operation(summary = "Public rejection of the onboarding link",
            description = "Rejects the token-addressed link before signup/login.")
    @PostMapping("/link/reject")
    public Map<String, Object> publicReject(@Valid @RequestBody PublicLinkRejectRequest request) {
        return linkRequestService.rejectPublicByToken(request.token());
    }

    @Operation(summary = "Review the link I was sent",
            description = "Loads the token-addressed link request, proves I own it (ownership guard + PAN), and returns "
                    + "the distributor display name, the distributor-entered Step-1 profile details, status and expiry. "
                    + "404 if the token is unknown; 403 if the link is not mine.")
    @GetMapping("/link/{token}")
    public InvestorLinkReviewResponse review(@PathVariable String token, Authentication auth) {
        UUID accountId = accountId(auth);
        InvestorLinkRequest request = requireOwnedRequest(accountId, token);

        String distributorName = distributorRepository.findById(request.getPendingDistributorId())
                .map(Distributor::getFullName)
                .orElse(null);

        Optional<OnboardingSubmission> submission =
                onboardingSubmissionService.findById(request.getOnboardingSubmissionId());
        String profileDetailsJson = submission
                .map(OnboardingSubmission::getPayloadJson)
                .orElseGet(() -> fallbackProfileDetailsJson(request));

        return new InvestorLinkReviewResponse(
                distributorName,
                profileDetailsJson,
                submission.map(OnboardingSubmission::getContentSha256).orElse(null),
                submission.map(OnboardingSubmission::getRevisionNo).orElse(null),
                request.getStatus().name(),
                request.getExpiresAt());
    }

    @Operation(summary = "Approve the link",
            description = "Links the distributor to my investor profile by PAN (distributor_id ← pending_distributor_id) "
                    + "and flips linking_status to INVESTOR_APPROVED. The account id is taken from my JWT, never the body. "
                    + "Propagates the service's 403/expired/already-approved errors.")
    @PostMapping("/link/{token}/approve")
    public Map<String, Object> approve(@PathVariable String token, Authentication auth) {
        UUID accountId = accountId(auth);
        // The service re-proves ownership + PAN-binds and drives the INVESTOR_APPROVED transition;
        // it also surfaces 404 (unknown token), 403 (not mine), and expired/already-approved.
        InvestorLinkRequest approved = linkRequestService.approveByToken(accountId, token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linkingStatus", InvestorLinkingStatus.INVESTOR_APPROVED.name());
        body.put("status", approved.getStatus().name());
        return body;
    }

    @Operation(summary = "Reject the link",
            description = "Declines the link: marks the request REJECTED and the investor's linking_status REJECTED. "
                    + "Ownership-checked (guard + PAN) so I cannot reject someone else's link. The account id is taken "
                    + "from my JWT, never the body.")
    @PostMapping("/link/{token}/reject")
    public Map<String, Object> reject(@PathVariable String token, Authentication auth) {
        UUID accountId = accountId(auth);
        // reject(accountId, token) does the ownership guard + PAN bind at the service layer.
        linkRequestService.reject(accountId, token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linkingStatus", InvestorLinkingStatus.REJECTED.name());
        return body;
    }

    // ── Skip-form (R7e/R10) ───────────────────────────────────────────────────

    @Operation(summary = "Approve the link but skip filling the form",
            description = "I approve the link but ask my distributor to fill the profile (R10). The token-addressed link "
                    + "is loaded, I prove I own it (guard + PAN), and linking_status flips to INVESTOR_SKIPPED. The acting "
                    + "investor id is resolved from the OWNED link request, never the body. 404 if the token is unknown; "
                    + "403 if the link is not mine.")
    @PostMapping("/link/{token}/approve-and-skip")
    public Map<String, Object> approveAndSkip(@PathVariable String token, Authentication auth) {
        UUID accountId = accountId(auth);
        // requireOwnedRequest proves the authenticated account owns this link (guard + PAN);
        // the investor id is taken from THAT owned request, never from a body value.
        InvestorLinkRequest request = requireOwnedRequest(accountId, token);
        investorService.approveAndSkipForm(request.getInvestorId(), accountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linkingStatus", InvestorLinkingStatus.INVESTOR_SKIPPED.name());
        return body;
    }

    // ── Profile-change approvals (the investor Approvals page, R9/R10) ─────────

    @Operation(summary = "List my pending distributor-filled profile changes",
            description = "All live (PENDING | CHALLENGE_SENT | APPROVED) profile-change 2FA challenges for MY investor "
                    + "profile — the Approvals page data. Scoped to the investor my JWT account is linked to, so it can "
                    + "never surface another investor's challenges. Never returns the OTP.")
    @GetMapping("/profile-changes")
    public List<ProfileChangeApprovalSummaryResponse> listProfileChanges(Authentication auth) {
        UUID investorId = investorId(auth);
        return profileChangeApprovalService.listForInvestor(investorId).stream()
                .filter(c -> LIVE_PROFILE_STATUSES.contains(c.getStatus()))
                .map(ProfileChangeApprovalSummaryResponse::from)
                .toList();
    }

    @Operation(summary = "Send my profile-change approval OTP",
            description = "Emails the one-time passcode bound to this challenge (PENDING → CHALLENGE_SENT). I must own the "
                    + "target challenge (guard, via the service). Never returns the code.")
    @PostMapping("/profile-changes/{challengeId}/request-otp")
    public OtpRequestResponse requestProfileChangeOtp(@PathVariable UUID challengeId, Authentication auth) {
        UUID accountId = accountId(auth);
        requireOwnedChallenge(accountId, challengeId);
        // isDistributorResend=false: this is the investor's own request.
        return profileChangeApprovalService.requestApprovalOtp(challengeId, accountId, false);
    }

    @Operation(summary = "Approve a distributor-filled profile change with consent + OTP",
            description = "Verifies consent + the OTP bound to this challenge (→ APPROVED), then APPLIES the approved change "
                    + "(the atomic consume gate over a freshly-recomputed snapshot hash → READY). I must own the target "
                    + "challenge (guard). ip/ua/sessionId are captured from the request for the consent evidence.")
    @PostMapping("/profile-changes/{challengeId}/approve")
    public Map<String, Object> approveProfileChange(
            @PathVariable UUID challengeId,
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest,
            Authentication auth) {
        UUID accountId = accountId(auth);
        ProfileChangeApprovalChallenge challenge = requireOwnedChallenge(accountId, challengeId);
        UUID investorId = challenge.getInvestorId();

        String ip = clientIp(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        String sessionId = httpRequest.getRequestedSessionId();

        // 1) Verify consent + OTP and move the challenge → APPROVED (ownership re-asserted in the service).
        investorService.approveProfileChange(
                investorId, challengeId, accountId, request.code(), request.consentAccepted(), ip, ua, sessionId);

        // 2) Apply the approved change. Recompute the snapshot hash from the EXACT frozen pending
        // profile so the atomic consume gate matches; applying flips the challenge → CONSUMED and
        // drives the investor → READY.
        String recomputedSha256 = ConsentRecordService.sha256(challenge.getPendingProfileJson());
        investorService.applyProfileChange(investorId, recomputedSha256);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId);
        body.put("linkingStatus", InvestorLinkingStatus.READY.name());
        body.put("message", "Your profile changes have been approved and applied.");
        return body;
    }

    @Operation(summary = "Reject a distributor-filled profile change",
            description = "Declines the named challenge (→ REJECTED) and returns the link to INVESTOR_SKIPPED so the "
                    + "distributor can re-fill. I must own the target challenge (guard, via the service).")
    @PostMapping("/profile-changes/{challengeId}/reject")
    public Map<String, Object> rejectProfileChange(
            @PathVariable UUID challengeId,
            @RequestBody(required = false) ProfileChangeRejectRequest request,
            Authentication auth) {
        UUID accountId = accountId(auth);
        ProfileChangeApprovalChallenge challenge = requireOwnedChallenge(accountId, challengeId);
        String reason = request == null ? null : request.reason();
        investorService.rejectProfileChange(challenge.getInvestorId(), challengeId, reason, accountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeId", challengeId);
        body.put("linkingStatus", InvestorLinkingStatus.INVESTOR_SKIPPED.name());
        return body;
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Loads the link request by token (404 when unknown) and asserts the authenticated
     * investor owns it with the SAME rule {@code approveByToken} uses: the ownership guard
     * (account.investorId == request.investorId) PLUS a PAN match (403 otherwise). Used by the
     * read-only review endpoint, whose binding lives here rather than in the (write-path) service.
     */
    private record PublicLinkApprovalRequest(
            @NotBlank String token,
            Boolean consentAccepted
    ) {}

    private record PublicLinkRejectRequest(
            @NotBlank String token
    ) {}

    private InvestorLinkRequest requireOwnedRequest(UUID accountId, String token) {
        InvestorLinkRequest request = linkRequestRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Approval link not found or no longer valid."));
        InvestorAccount account = ownershipGuard.assertOwns(accountId, request.getInvestorId());
        if (!Objects.equals(normalizePan(account.getPan()), normalizePan(request.getPan()))) {
            throw new AccessDeniedException("This approval link does not belong to your account.");
        }
        return request;
    }

    private static String normalizePan(String value) {
        return PanFormat.normalize(value);
    }

    private String fallbackProfileDetailsJson(InvestorLinkRequest request) {
        try {
            Investor investor = investorService.getInvestor(request.getInvestorId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fullName", investor.getFullName());
            details.put("pan", investor.getPan());
            details.put("email", investor.getEmail());
            details.put("mobileNumber", investor.getMobileNumber());
            details.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
            details.put("linkingStatus", investor.getLinkingStatus() == null ? null : investor.getLinkingStatus().name());
            return LINK_REVIEW_MAPPER.writeValueAsString(details);
        } catch (JsonProcessingException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Resolves the authenticated investor account id from the JWT principal — the SAME
     * resolution {@link InvestorPortalController} uses. A non-investor (e.g. distributor)
     * principal is structurally rejected (403) because it does not implement
     * {@link InvestorAuthPrincipal}. {@code requireAccount} additionally enforces the
     * account is still ACTIVE on every call.
     */
    private UUID accountId(Authentication auth) {
        return requireAccount(auth).getId();
    }

    /**
     * Resolves the authenticated investor id from the JWT principal: the active account,
     * then the distributor-created investor it is confirmed-linked to (by PAN). A non-investor
     * principal is rejected (403); an account not yet linked to an investor profile is a 400.
     */
    private UUID investorId(Authentication auth) {
        InvestorAccount account = requireAccount(auth);
        UUID investorId = account.getInvestorId();
        if (investorId == null) {
            throw new AccessDeniedException("Your account is not linked to an investor profile yet.");
        }
        return investorId;
    }

    private InvestorAccount requireAccount(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof InvestorAuthPrincipal principal)) {
            throw new AccessDeniedException("Authenticated investor principal is required");
        }
        return investorAuthService.requireAccount(principal.getInvestorAccountId());
    }

    /**
     * Loads a profile-change challenge by id (404 when unknown) and asserts the authenticated
     * investor account owns the investor that challenge belongs to (403 otherwise), via the
     * shared {@link InvestorAccountOwnershipGuard}. This is the per-resource IDOR guard that
     * keeps one investor from acting on another investor's challenge by a guessed id.
     */
    private ProfileChangeApprovalChallenge requireOwnedChallenge(UUID accountId, UUID challengeId) {
        ProfileChangeApprovalChallenge challenge = profileChangeApprovalService.getChallenge(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Approval challenge not found."));
        ownershipGuard.assertOwns(accountId, challenge.getInvestorId());
        return challenge;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

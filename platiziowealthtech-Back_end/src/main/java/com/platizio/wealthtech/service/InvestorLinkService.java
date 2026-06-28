package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.DistributorNotification;
import com.platizio.wealthtech.domain.DistributorNotificationType;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.LinkRequestStatus;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.repository.DistributorNotificationRepository;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorLinkRequestRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The investor&lt;-&gt;distributor approval-link lifecycle (investor.md M2, R1/R2/R3/R5/R7).
 *
 * <p>After a distributor enters Step-1 basic identity for a <em>pending</em> investor
 * ({@code linking_status = PENDING_INVESTOR_APPROVAL}, {@code distributor_id = NULL}),
 * {@link #sendToInvestor} freezes the entered details into a hashed review payload, mints
 * an opaque email-link token (only its SHA-256 is stored), and emails it. The investor
 * reviews ({@link #review}) and approves ({@link #approve}) or rejects ({@link #reject})
 * with no session — the token is the possession factor. On approve, {@code distributor_id}
 * is linked by PAN and {@code linking_status} becomes {@code INVESTOR_APPROVED}.
 */
@Service
public class InvestorLinkService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorLinkService.class);
    private static final String CONSENT_KEY = "investor_link_approval";
    private static final String CONSENT_VERSION = "v1";

    private final InvestorRepository investorRepository;
    private final InvestorLinkRequestRepository linkRequestRepository;
    private final DistributorNotificationRepository notificationRepository;
    private final DistributorRepository distributorRepository;
    private final ConsentRecordService consentRecordService;
    private final EmailService emailService;
    private final OnboardingSubmissionService onboardingSubmissionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private final String portalBaseUrl;
    private final long expiryDays;

    public InvestorLinkService(
            InvestorRepository investorRepository,
            InvestorLinkRequestRepository linkRequestRepository,
            DistributorNotificationRepository notificationRepository,
            DistributorRepository distributorRepository,
            ConsentRecordService consentRecordService,
            EmailService emailService,
            OnboardingSubmissionService onboardingSubmissionService,
            @Value("${app.investor-link.portal-base-url:http://localhost:3001}") String portalBaseUrl,
            @Value("${app.investor-link.expiry-days:7}") long expiryDays) {
        this.investorRepository = investorRepository;
        this.linkRequestRepository = linkRequestRepository;
        this.notificationRepository = notificationRepository;
        this.distributorRepository = distributorRepository;
        this.consentRecordService = consentRecordService;
        this.emailService = emailService;
        this.onboardingSubmissionService = onboardingSubmissionService;
        this.portalBaseUrl = portalBaseUrl;
        this.expiryDays = expiryDays;
    }

    // ── T1: distributor sends the Step-1 identity to the investor (R1/R3) ───────
    @Transactional
    public Map<String, Object> sendToInvestor(UUID investorId, UUID distributorActorId) {
        // Accept any investor owned by this distributor — both a fresh gated create
        // (the "send link, investor fills" option) AND a distributor-filled draft
        // (the "I fill, investor approves" option). Either way the investor reviews
        // and approves via the no-auth link before the profile is confirmed.
        Investor investor = requireOwned(investorId, distributorActorId);
        if (investor.getLinkingStatus() != InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL) {
            investor.setLinkingStatus(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
            investorRepository.save(investor);
        }

        // Only one live request ever: supersede any prior PENDING one (re-send).
        linkRequestRepository
                .findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(investorId, LinkRequestStatus.PENDING)
                .ifPresent(prior -> {
                    prior.setStatus(LinkRequestStatus.SUPERSEDED);
                    linkRequestRepository.save(prior);
                });

        String reviewJson = buildReviewPayload(investor);
        String rawToken = mintToken();
        OffsetDateTime now = OffsetDateTime.now();

        InvestorLinkRequest link = new InvestorLinkRequest();
        link.setInvestorId(investorId);
        link.setPan(investor.getPan());
        link.setDistributorId(distributorActorId);
        link.setTokenHash(ConsentRecordService.sha256(rawToken));
        link.setStatus(LinkRequestStatus.PENDING);
        link.setReviewPayloadJson(reviewJson);
        link.setReviewSha256(ConsentRecordService.sha256(reviewJson));
        link.setSentToEmail(investor.getEmail());
        link.setExpiresAt(now.plusDays(expiryDays));
        linkRequestRepository.save(link);

        // The link opens a PUBLIC, no-auth form prefilled with the distributor's details.
        String approveUrl = portalBaseUrl + "/investor/link-form?token=" + rawToken;
        boolean delivered = sendEmail(investor, approveUrl);
        if (!delivered) {
            logger.info("investor_link status='email_disabled' investor_id='{}' approve_url='{}'", investorId, approveUrl);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL.name());
        out.put("expiresAt", link.getExpiresAt());
        out.put("emailDelivered", delivered);
        // The shareable link is ALWAYS surfaced so the distributor can hand it over directly
        // (not only when SMTP is disabled). The token is the sole credential to open the form.
        out.put("approveUrl", approveUrl);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> linkStatus(UUID investorId, UUID distributorActorId) {
        Investor investor = requireOwned(investorId, distributorActorId);
        Optional<InvestorLinkRequest> live = linkRequestRepository
                .findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(investorId, LinkRequestStatus.PENDING);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkingStatus", investor.getLinkingStatus() == null ? null : investor.getLinkingStatus().name());
        out.put("linkPending", live.isPresent());
        out.put("expiresAt", live.map(InvestorLinkRequest::getExpiresAt).orElse(null));
        return out;
    }

    // ── Public (token-authenticated): investor reviews the link + form prefill ──
    @Transactional(readOnly = true)
    public Map<String, Object> review(String token) {
        InvestorLinkRequest link = requireLiveLink(token);

        // Prefer the LIVE investor entity (so the form prefills the latest distributor-entered
        // details); fall back to the frozen review snapshot if the investor row is gone.
        Investor investor = investorRepository.findById(link.getInvestorId()).orElse(null);
        JsonNode frozen = readJson(link.getReviewPayloadJson());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", link.getStatus() == null ? null : link.getStatus().name());
        out.put("fullName", investor != null ? investor.getFullName() : textOrNull(frozen, "fullName"));
        out.put("pan", investor != null ? investor.getPan() : textOrNull(frozen, "pan"));
        out.put("maskedEmail", maskEmail(link.getSentToEmail()));
        out.put("email", investor != null ? investor.getEmail() : textOrNull(frozen, "email"));
        out.put("mobileNumber", investor != null ? investor.getMobileNumber() : textOrNull(frozen, "mobileNumber"));
        out.put("dateOfBirth", investor != null
                ? (investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString())
                : textOrNull(frozen, "dateOfBirth"));
        out.put("relationshipType", investor != null
                ? (investor.getRelationshipType() == null ? null : investor.getRelationshipType().name())
                : textOrNull(frozen, "relationshipType"));
        out.put("distributorName", distributorName(link.getDistributorId()));
        out.put("profile", reviewProfile(investor, frozen));
        out.put("expiresAt", link.getExpiresAt());
        return out;
    }

    /** The investor's already-entered profile fields, for prefilling the no-auth form. */
    private Map<String, Object> reviewProfile(Investor investor, JsonNode frozen) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (investor != null) {
            p.put("fullName", investor.getFullName());
            p.put("pan", investor.getPan());
            p.put("email", investor.getEmail());
            p.put("mobileNumber", investor.getMobileNumber());
            p.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
            p.put("relationshipType",
                    investor.getRelationshipType() == null ? null : investor.getRelationshipType().name());
            p.put("addressLine1", investor.getAddressLine1());
            p.put("addressLine2", investor.getAddressLine2());
            p.put("city", investor.getCity());
            p.put("state", investor.getState());
            p.put("postalCode", investor.getPostalCode());
        } else {
            p.put("fullName", textOrNull(frozen, "fullName"));
            p.put("pan", textOrNull(frozen, "pan"));
            p.put("email", textOrNull(frozen, "email"));
            p.put("mobileNumber", textOrNull(frozen, "mobileNumber"));
            p.put("dateOfBirth", textOrNull(frozen, "dateOfBirth"));
            p.put("relationshipType", textOrNull(frozen, "relationshipType"));
        }
        return p;
    }

    private String distributorName(UUID distributorId) {
        if (distributorId == null) {
            return null;
        }
        return distributorRepository.findById(distributorId)
                .map(Distributor::getFullName)
                .orElse(null);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    // ── T2: investor approves; distributor_id is linked by PAN (R7b/R5) ────────
    @Transactional
    public Map<String, Object> approve(String token, boolean consentAccepted, String ip, String ua) {
        if (!consentAccepted) {
            throw new IllegalArgumentException("You must accept the consent to approve this link.");
        }
        InvestorLinkRequest link = requireLiveLink(token);
        Investor investor = investorRepository.findById(link.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));

        var consent = consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, investor.getId(), CONSENT_KEY, CONSENT_VERSION,
                consentText(), ip, ua);

        OffsetDateTime now = OffsetDateTime.now();
        link.setStatus(LinkRequestStatus.APPROVED);
        link.setApprovedAt(now);
        link.setApprovalIp(ip);
        link.setApprovalUa(ua);
        link.setConsentRecordId(consent.getId());
        linkRequestRepository.save(link);

        // R5: the link is keyed on PAN — set distributor_id now that the investor approved.
        investor.setDistributorId(link.getDistributorId());
        investor.setPendingDistributorId(null);
        investor.setLinkingStatus(InvestorLinkingStatus.INVESTOR_APPROVED);
        investorRepository.save(investor);

        notify(link.getDistributorId(), investor.getId(), DistributorNotificationType.INVESTOR_APPROVED,
                "Investor approved", safeName(investor) + " approved your onboarding link.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investorId", investor.getId());
        out.put("pan", investor.getPan());
        out.put("email", investor.getEmail());
        out.put("linkingStatus", investor.getLinkingStatus().name());
        return out;
    }

    @Transactional
    public Map<String, Object> reject(String token, String ip, String ua) {
        InvestorLinkRequest link = requireLiveLink(token);
        Investor investor = investorRepository.findById(link.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        OffsetDateTime now = OffsetDateTime.now();
        link.setStatus(LinkRequestStatus.REJECTED);
        link.setRejectedAt(now);
        link.setApprovalIp(ip);
        link.setApprovalUa(ua);
        linkRequestRepository.save(link);

        investor.setLinkingStatus(InvestorLinkingStatus.REJECTED);
        investorRepository.save(investor);

        notify(link.getDistributorId(), investor.getId(), DistributorNotificationType.LINK_REJECTED,
                "Investor declined", safeName(investor) + " declined your onboarding link.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", LinkRequestStatus.REJECTED.name());
        return out;
    }

    // ── No-auth link form: investor reviews+completes the form and submits (token-only) ──

    /**
     * The investor opens the no-auth link, reviews/completes the prefilled form, and clicks
     * "Approve &amp; Submit". The opaque {@code token} is the only credential. This records the
     * approval consent, links {@code distributor_id} by the request, applies the profile via the
     * existing {@link #submitProfileToDistributor} pipeline (auto-attest → {@code READY} → notify),
     * and consumes the token. No investor account/session exists, so {@code accountId} is null —
     * the downstream pipeline records "investor via link".
     */
    @Transactional
    public Map<String, Object> submitProfileByToken(
            String token, JsonNode profile, boolean consentAccepted, String ip, String ua) {
        if (!consentAccepted) {
            throw new IllegalArgumentException("You must accept the consent to submit this form.");
        }
        InvestorLinkRequest link = requireLiveLink(token);
        Investor investor = investorRepository.findById(link.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));

        // Record the approval consent against the investor (mirrors approve()).
        var consent = consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, investor.getId(), CONSENT_KEY, CONSENT_VERSION,
                consentText(), ip, ua);

        // Link the distributor now that the investor approved (keyed by the request).
        investor.setDistributorId(link.getDistributorId());
        investor.setPendingDistributorId(null);
        investorRepository.save(investor);

        // Apply the profile + auto-attest + READY + notify (accountId null = investor via link).
        submitProfileToDistributor(link.getInvestorId(), null, profile, ip, ua);

        // Consume the token.
        OffsetDateTime now = OffsetDateTime.now();
        link.setStatus(LinkRequestStatus.APPROVED);
        link.setApprovedAt(now);
        link.setApprovalIp(ip);
        link.setApprovalUa(ua);
        link.setConsentRecordId(consent.getId());
        linkRequestRepository.save(link);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", InvestorLinkingStatus.READY.name());
        out.put("message", "Submitted to your distributor.");
        return out;
    }

    // ── M3: investor self-authors the profile and sends it to the distributor (R7e/R7f/R10) ──

    /** Prefill for the investor profile form: the basic identity already on file. */
    @Transactional(readOnly = true)
    public Map<String, Object> profilePrefill(UUID investorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fullName", investor.getFullName());
        out.put("pan", investor.getPan());
        out.put("email", investor.getEmail());
        out.put("mobileNumber", investor.getMobileNumber());
        out.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
        out.put("linkingStatus", investor.getLinkingStatus() == null ? null : investor.getLinkingStatus().name());
        return out;
    }

    /**
     * T5: the investor fills the form and sends it to the distributor. The investor is both
     * author and approver of their own data (investor.md D3), so we AUTO-ATTEST an
     * {@link OnboardingSubmission} — reusing the existing finalize gate unchanged — then mark
     * {@code linking_status = READY} and notify the distributor (R8).
     */
    @Transactional
    public Map<String, Object> submitProfileToDistributor(UUID investorId, UUID accountId, JsonNode profile, String ip, String ua) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        UUID distributorId = investor.getDistributorId();
        if (distributorId == null) {
            throw new IllegalStateException("Your account is not linked to a distributor yet.");
        }
        // No-auth link flow has no investor session: the investor acts on their own behalf, so
        // attribute the submission/attestation to the investor itself ("investor via link"). This
        // also satisfies the NOT NULL submittedBy / audit actor_id columns without an account.
        UUID effectiveActorId = accountId != null ? accountId : investorId;
        // The investor has the liberty to edit the details the distributor entered —
        // apply those edits to the live record before freezing the attested snapshot.
        applyProfileToInvestor(investor, profile);
        String payloadJson = buildProfilePayload(investor, profile);
        var submission = onboardingSubmissionService.submitForInvestorReview(investorId, payloadJson, effectiveActorId);
        onboardingSubmissionService.attest(investorId, effectiveActorId, submission.getContentSha256(), ip, ua);

        investor.setLinkingStatus(InvestorLinkingStatus.READY);
        investorRepository.save(investor);

        notify(distributorId, investorId, DistributorNotificationType.INVESTOR_FORM_SUBMITTED,
                "Investor submitted profile", safeName(investor) + " completed and submitted their profile.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkingStatus", InvestorLinkingStatus.READY.name());
        out.put("submissionId", submission.getId());
        return out;
    }

    /** T5': the investor approves the link but skips the form; the distributor fills it (R10). */
    @Transactional
    public Map<String, Object> skipForm(UUID investorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        UUID distributorId = investor.getDistributorId();
        if (distributorId == null) {
            throw new IllegalStateException("Your account is not linked to a distributor yet.");
        }
        investor.setLinkingStatus(InvestorLinkingStatus.INVESTOR_SKIPPED);
        investorRepository.save(investor);
        notify(distributorId, investorId, DistributorNotificationType.INVESTOR_SKIPPED,
                "Investor skipped the form", safeName(investor) + " approved but skipped the form — you can fill it for them.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkingStatus", InvestorLinkingStatus.INVESTOR_SKIPPED.name());
        return out;
    }

    /**
     * Applies the investor-edited identity + address fields (from the public link form)
     * onto the live {@link Investor} record. Only non-blank values overwrite — clearing a
     * prefilled field leaves the existing value. Bank/FATCA fields stay in the attested
     * snapshot (no dedicated entity columns). Lets the investor correct anything the
     * distributor entered before the profile is confirmed.
     */
    private void applyProfileToInvestor(Investor investor, JsonNode profile) {
        if (profile == null || !profile.isObject()) {
            return;
        }
        applyText(profile, "fullName", investor::setFullName);
        applyText(profile, "email", investor::setEmail);
        applyText(profile, "mobileNumber", investor::setMobileNumber);
        applyText(profile, "addressLine1", investor::setAddressLine1);
        applyText(profile, "addressLine2", investor::setAddressLine2);
        applyText(profile, "city", investor::setCity);
        applyText(profile, "state", investor::setState);
        applyText(profile, "postalCode", investor::setPostalCode);
        String pan = textOrNull(profile, "pan");
        if (pan != null && !pan.isBlank()) {
            investor.setPan(pan.trim().toUpperCase());
        }
        String dob = textOrNull(profile, "dateOfBirth");
        if (dob != null && !dob.isBlank()) {
            try {
                investor.setDateOfBirth(java.time.LocalDate.parse(dob.trim()));
            } catch (java.time.format.DateTimeParseException ignored) {
                // keep the existing DOB if the edit isn't a valid ISO date
            }
        }
        String rel = textOrNull(profile, "relationshipType");
        if (rel != null && !rel.isBlank()) {
            try {
                investor.setRelationshipType(
                        com.platizio.wealthtech.domain.InvestorRelationshipType.valueOf(rel.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // keep the existing relationship if the value isn't a known type
            }
        }
    }

    private void applyText(JsonNode profile, String key, java.util.function.Consumer<String> setter) {
        String v = textOrNull(profile, key);
        if (v != null && !v.isBlank()) {
            setter.accept(v.trim());
        }
    }

    private String buildProfilePayload(Investor investor, JsonNode profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", investor.getFullName());
        m.put("pan", investor.getPan());
        m.put("email", investor.getEmail());
        m.put("mobileNumber", investor.getMobileNumber());
        m.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
        m.put("authoredBy", "INVESTOR");
        if (profile != null && !profile.isNull()) {
            m.put("profile", profile);
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the profile payload", e);
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private Investor requireOwned(UUID investorId, UUID actorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        boolean owns = actorId != null
                && (actorId.equals(investor.getPendingDistributorId()) || actorId.equals(investor.getDistributorId()));
        if (!owns) {
            throw new AccessDeniedException("Investor does not belong to the authenticated distributor");
        }
        return investor;
    }

    private Investor requirePendingOwned(UUID investorId, UUID actorId) {
        Investor investor = requireOwned(investorId, actorId);
        if (investor.getLinkingStatus() != InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL) {
            throw new IllegalStateException("This investor is not awaiting an approval link.");
        }
        return investor;
    }

    private InvestorLinkRequest requireLiveLink(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("A link token is required.");
        }
        InvestorLinkRequest link = linkRequestRepository.findFirstByTokenHash(ConsentRecordService.sha256(token))
                .orElseThrow(() -> new IllegalArgumentException("This link is invalid or has already been used."));
        if (link.getStatus() != LinkRequestStatus.PENDING) {
            throw new IllegalArgumentException("This link is no longer active.");
        }
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(OffsetDateTime.now())) {
            link.setStatus(LinkRequestStatus.EXPIRED);
            linkRequestRepository.save(link);
            throw new IllegalArgumentException("This link has expired. Ask your distributor to resend it.");
        }
        return link;
    }

    private void notify(UUID distributorId, UUID investorId, DistributorNotificationType type, String title, String body) {
        DistributorNotification n = new DistributorNotification();
        n.setDistributorId(distributorId);
        n.setInvestorId(investorId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        notificationRepository.save(n);
    }

    private String buildReviewPayload(Investor investor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", investor.getFullName());
        m.put("pan", investor.getPan());
        m.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
        m.put("email", investor.getEmail());
        m.put("mobileNumber", investor.getMobileNumber());
        m.put("relationshipType", investor.getRelationshipType() == null ? null : investor.getRelationshipType().name());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the link review payload", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String mintToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean sendEmail(Investor investor, String approveUrl) {
        String subject = "Approve your Platizio onboarding";
        String html = """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:auto;color:#0B1B3E">
                  <h2 style="margin-bottom:4px">Platizio</h2>
                  <p style="color:#475569;font-size:14px">Your distributor has started onboarding you. Review the
                  details and approve to continue. This link expires in %d days.</p>
                  <p style="margin:20px 0"><a href="%s" style="background:#0B1B3E;color:#fff;text-decoration:none;
                  padding:12px 20px;border-radius:10px;font-weight:600">Review &amp; approve</a></p>
                  <p style="color:#94a3b8;font-size:12px">If you didn't expect this, you can safely ignore this email.</p>
                </div>
                """.formatted(expiryDays, approveUrl);
        return emailService.sendHtml(investor.getEmail(), subject, html);
    }

    private String consentText() {
        return "I confirm the details shown are mine and I authorise this distributor to onboard me on Platizio.";
    }

    private String safeName(Investor investor) {
        return StringUtils.hasText(investor.getFullName()) ? investor.getFullName() : "The investor";
    }

    private String maskEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@", 2);
        String name = parts[0];
        String shown = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return shown + "***@" + parts[1];
    }
}

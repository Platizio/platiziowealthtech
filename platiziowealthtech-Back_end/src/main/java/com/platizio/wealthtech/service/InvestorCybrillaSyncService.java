package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.RiskProfileType;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Reconciles Fintech Primitives investor profiles into the local PostgreSQL cache.
 * Cybrilla/FP is the catalogue of record; the DB stores distributor mapping and workflow state.
 *
 * Finprim has no dedicated "sync" API — only {@code GET /v2/investor_profiles} (list) and
 * {@code GET /v2/investor_profiles/:id} (fetch). Passive list/search reads call that list endpoint
 * and reconcile linked rows in PostgreSQL before returning the UI response.
 * {@code POST /investors/sync-from-cybrilla} restores archived investors that still exist in Finprim.
 * Optional {@code importNew=true} imports unlinked Finprim profiles (sandbox cleanup use only).
 */
@Service
public class InvestorCybrillaSyncService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorCybrillaSyncService.class);

    private final CybrillaClient cybrillaClient;
    private final InvestorRepository investorRepository;
    private final Duration syncMinInterval;
    private final String investorDataSource;
    private final TransactionTemplate profileTransactionTemplate;

    private volatile Instant lastSuccessfulSyncAt;
    private volatile InvestorSyncResult lastSyncResult = InvestorSyncResult.empty();

    @Autowired
    public InvestorCybrillaSyncService(
            CybrillaClient cybrillaClient,
            InvestorRepository investorRepository,
            @Value("${cybrilla.integration.investor-sync-min-interval-minutes:5}") int syncMinIntervalMinutes,
            @Value("${cybrilla.integration.investor-data-source:cybrilla}") String investorDataSource,
            PlatformTransactionManager transactionManager
    ) {
        this.cybrillaClient = cybrillaClient;
        this.investorRepository = investorRepository;
        this.syncMinInterval = Duration.ofMinutes(Math.max(0, syncMinIntervalMinutes));
        this.investorDataSource = investorDataSource == null ? "cybrilla" : investorDataSource.trim().toLowerCase(Locale.ROOT);
        if (transactionManager == null) {
            this.profileTransactionTemplate = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.profileTransactionTemplate = template;
        }
    }

    public boolean usesCybrillaInvestorSource() {
        return !"local".equals(investorDataSource);
    }

    public InvestorSyncResult getLastSyncResult() {
        return lastSyncResult;
    }

    public void syncBeforeRead(UUID distributorId, boolean cybrillaSourceEnabled, boolean explicitSync, boolean forceSync) {
        if (!cybrillaSourceEnabled && !explicitSync) {
            return;
        }
        // Cybrilla-backed list reads always call GET /v2/investor_profiles (no Finprim "sync" API exists).
        // Rate-limit only optional explicit GET ?syncFromCybrilla=true refreshes when Cybrilla source is off.
        if (!forceSync && !cybrillaSourceEnabled && !explicitSync && shouldSkipRecentSync()) {
            logger.info(
                    "investor_profile_sync status='skipped_recent' distributor_id='{}' min_interval_minutes='{}'",
                    distributorId,
                    syncMinInterval.toMinutes()
            );
            return;
        }
        logger.info(
                "investor_profile_sync status='started' distributor_id='{}' trigger='passive_read' force='{}' restore_archived='false'",
                distributorId,
                forceSync
        );
        try {
            InvestorSyncResult result = syncProfilesForDistributor(distributorId, false, false);
            lastSyncResult = result;
        } catch (CybrillaUnavailableException ex) {
            logger.warn(
                    "investor_profile_sync status='skipped_unavailable' distributor_id='{}' reason='{}'",
                    distributorId,
                    ex.getMessage()
            );
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "investor_profile_sync status='failed' distributor_id='{}' reason='{}'",
                    distributorId,
                    ex.getMessage()
            );
        }
    }

    public InvestorSyncResult syncProfilesForDistributor(UUID distributorId, boolean restoreArchived, boolean importNew) {
        JsonNode response = cybrillaClient.listInvestorProfiles(null, "individual");
        JsonNode profiles = response == null ? null : response.get("data");
        if (profiles == null || !profiles.isArray()) {
            throw new CybrillaApiException("Fintech Primitives investor profile list did not return a data array");
        }

        int restored = 0;
        int imported = 0;
        int updated = 0;
        int skippedUnlinked = 0;
        int skippedArchived = 0;
        int failed = 0;

        for (Iterator<JsonNode> iterator = profiles.elements(); iterator.hasNext(); ) {
            JsonNode profile = iterator.next();
            try {
                ReconcileOutcome outcome = reconcileProfileIsolated(distributorId, profile, restoreArchived, importNew);
                restored += outcome.restored();
                imported += outcome.imported();
                updated += outcome.updated();
                skippedUnlinked += outcome.skippedUnlinked();
                skippedArchived += outcome.skippedArchived();
            } catch (RuntimeException ex) {
                failed++;
                logger.error(
                        "investor_profile_sync status='profile_failed' distributor_id='{}' profile_id='{}' pan='{}' reason='{}'",
                        distributorId,
                        text(profile, "id"),
                        normalizePan(text(profile, "pan")),
                        ex.getMessage(),
                        ex
                );
            }
        }

        lastSuccessfulSyncAt = Instant.now();
        InvestorSyncResult result = new InvestorSyncResult(
                profiles.size(),
                restored,
                imported,
                updated,
                skippedUnlinked,
                skippedArchived,
                failed,
                restoreArchived,
                importNew
        );
        lastSyncResult = result;
        logger.info(
                "investor_profile_sync status='completed' distributor_id='{}' restored='{}' imported='{}' updated='{}' skipped_unlinked='{}' skipped_archived='{}' failed='{}' provider_count='{}' restore_archived='{}' import_new='{}'",
                distributorId,
                restored,
                imported,
                updated,
                skippedUnlinked,
                skippedArchived,
                failed,
                profiles.size(),
                restoreArchived,
                importNew
        );
        if (failed > 0 && restored == 0 && imported == 0 && updated == 0) {
            throw new CybrillaApiException(
                    "Finprim returned "
                            + profiles.size()
                            + " profile(s) but none could be reconciled into the local cache ("
                            + failed
                            + " failed). Check backend logs for investor_profile_sync profile_failed entries."
            );
        }
        return result;
    }

    private ReconcileOutcome reconcileProfileIsolated(
            UUID distributorId,
            JsonNode profile,
            boolean restoreArchived,
            boolean importNew
    ) {
        if (profileTransactionTemplate == null) {
            return reconcileProfile(distributorId, profile, restoreArchived, importNew);
        }
        return profileTransactionTemplate.execute(status -> reconcileProfile(distributorId, profile, restoreArchived, importNew));
    }

    @Transactional
    public Investor restoreProfileForDistributor(UUID distributorId, String cybrillaInvestorId, String pan) {
        if (distributorId == null) {
            throw new CybrillaApiException("Distributor id is required to restore an investor profile");
        }
        JsonNode profile = null;
        if (StringUtils.hasText(cybrillaInvestorId)) {
            profile = cybrillaClient.fetchInvestorProfile(cybrillaInvestorId.trim());
        } else if (StringUtils.hasText(pan)) {
            JsonNode response = cybrillaClient.listInvestorProfiles(pan.trim(), "individual");
            JsonNode profiles = response == null ? null : response.get("data");
            if (profiles != null && profiles.isArray()) {
                for (Iterator<JsonNode> iterator = profiles.elements(); iterator.hasNext(); ) {
                    JsonNode candidate = iterator.next();
                    if (pan.trim().equalsIgnoreCase(normalizePan(text(candidate, "pan")))) {
                        profile = candidate;
                        break;
                    }
                }
            }
        } else {
            throw new CybrillaApiException("Cybrilla investor profile id or PAN is required");
        }
        if (profile == null || profile.isNull() || profile.isMissingNode()) {
            throw new CybrillaApiException("Investor profile was not found in Finprim for the supplied identifier");
        }

        ReconcileOutcome outcome = reconcileProfile(distributorId, profile, true, true);
        if (outcome.restored() == 0 && outcome.imported() == 0 && outcome.updated() == 0) {
            throw new CybrillaApiException("Investor profile exists in Finprim but could not be linked to this distributor");
        }

        String externalProfileId = text(profile, "id");
        String panValue = normalizePan(text(profile, "pan"));
        Optional<Investor> restored = investorRepository.findIncludingDeletedByCybrillaInvestorId(externalProfileId)
                .filter(investor -> distributorId.equals(investor.getDistributorId()));
        if (restored.isEmpty() && StringUtils.hasText(panValue)) {
            restored = investorRepository.findIncludingDeletedByPanAndDistributor(panValue, distributorId);
        }
        investorRepository.flush();
        return restored
                .map(investor -> {
                    investor.setIsDeleted(false);
                    investor.setDeletedAt(null);
                    ensurePersistableDefaults(investor);
                    return investorRepository.save(investor);
                })
                .orElseThrow(() -> new CybrillaApiException("Restored investor could not be loaded from the local cache"));
    }

    private ReconcileOutcome reconcileProfile(
            UUID distributorId,
            JsonNode profile,
            boolean restoreArchived,
            boolean importNew
    ) {
        String externalProfileId = text(profile, "id");
        String pan = normalizePan(text(profile, "pan"));
        if (!StringUtils.hasText(externalProfileId) || !StringUtils.hasText(pan)) {
            return ReconcileOutcome.none();
        }

        Optional<Investor> existing = resolveExistingInvestor(distributorId, externalProfileId, pan);
        if (existing.isPresent()) {
            Investor investor = existing.get();
            boolean wasDeleted = Boolean.TRUE.equals(investor.getIsDeleted());
            if (wasDeleted && !restoreArchived) {
                return new ReconcileOutcome(0, 0, 0, 0, 1);
            }
            applyProfile(investor, profile, externalProfileId, pan);
            ensurePersistableDefaults(investor);
            if (wasDeleted) {
                investor.setIsDeleted(false);
                investor.setDeletedAt(null);
                investorRepository.save(investor);
                return new ReconcileOutcome(1, 0, 0, 0, 0);
            }
            investorRepository.save(investor);
            return new ReconcileOutcome(0, 0, 1, 0, 0);
        }

        if (!importNew || distributorId == null) {
            return new ReconcileOutcome(0, 0, 0, 1, 0);
        }

        Optional<Investor> panOwnedElsewhere = investorRepository.findIncludingDeletedByPan(pan);
        if (panOwnedElsewhere.isPresent() && !distributorId.equals(panOwnedElsewhere.get().getDistributorId())) {
            return new ReconcileOutcome(0, 0, 0, 1, 0);
        }

        Investor importedInvestor = new Investor();
        importedInvestor.setDistributorId(distributorId);
        applyProfile(importedInvestor, profile, externalProfileId, pan);
        applyImportedContactDefaults(importedInvestor, pan);
        importedInvestor.setInvestorStatus(InvestorStatus.ONBOARDING);
        importedInvestor.setKycStatus(KycStatus.PENDING);
        importedInvestor.setHouseholdId(UUID.randomUUID());
        ensurePersistableDefaults(importedInvestor);
        Investor saved = investorRepository.save(importedInvestor);
        if (saved.getId() != null) {
            saved.setHouseholdId(saved.getId());
            investorRepository.save(saved);
        }
        return new ReconcileOutcome(0, 1, 0, 0, 0);
    }

    private Optional<Investor> resolveExistingInvestor(UUID distributorId, String externalProfileId, String pan) {
        Optional<Investor> byCybrillaId = investorRepository.findIncludingDeletedByCybrillaInvestorId(externalProfileId);
        if (byCybrillaId.isPresent()) {
            Investor match = byCybrillaId.get();
            if (distributorId != null && !distributorId.equals(match.getDistributorId())) {
                return Optional.empty();
            }
            return byCybrillaId;
        }

        Optional<Investor> byPan = distributorId == null
                ? investorRepository.findIncludingDeletedByPan(pan)
                : investorRepository.findIncludingDeletedByPanAndDistributor(pan, distributorId);
        if (byPan.isEmpty()) {
            return Optional.empty();
        }

        Investor candidate = byPan.get();
        String linkedCybrillaId = candidate.getCybrillaInvestorId();
        if (StringUtils.hasText(linkedCybrillaId) && !linkedCybrillaId.equals(externalProfileId)) {
            return Optional.empty();
        }
        return byPan;
    }

    private void applyProfile(Investor investor, JsonNode profile, String externalProfileId, String pan) {
        investor.setCybrillaInvestorId(externalProfileId);
        investor.setPan(pan);
        String name = text(profile, "name");
        if (StringUtils.hasText(name)) {
            investor.setFullName(name.trim());
        }
        String dateOfBirth = text(profile, "date_of_birth");
        if (StringUtils.hasText(dateOfBirth)) {
            try {
                investor.setDateOfBirth(LocalDate.parse(dateOfBirth.trim()));
            } catch (DateTimeParseException ex) {
                logger.warn(
                        "investor_profile_sync status='ignored_invalid_dob' profile_id='{}' dob='{}'",
                        externalProfileId,
                        dateOfBirth
                );
            }
        }
        investor.setExternalSyncPending(false);
        investor.setExternalSyncMessage(null);
    }

    private void applyImportedContactDefaults(Investor investor, String pan) {
        if (!StringUtils.hasText(investor.getEmail())) {
            investor.setEmail("cybrilla+" + pan.toLowerCase(Locale.ROOT) + "@sync.platizio.local");
        }
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            investor.setMobileNumber("9000000000");
        }
        if (!StringUtils.hasText(investor.getFullName())) {
            investor.setFullName("Investor " + pan);
        }
    }

    private void ensurePersistableDefaults(Investor investor) {
        if (investor.getHouseholdId() == null) {
            investor.setHouseholdId(investor.getId() != null ? investor.getId() : UUID.randomUUID());
        }
        if (investor.getInvestorStatus() == null) {
            investor.setInvestorStatus(InvestorStatus.ONBOARDING);
        }
        if (investor.getKycStatus() == null) {
            investor.setKycStatus(KycStatus.PENDING);
        }
        if (investor.getBankVerificationStatus() == null) {
            investor.setBankVerificationStatus(BankVerificationStatus.NOT_CAPTURED);
        }
        if (investor.getRiskProfile() == null) {
            investor.setRiskProfile(RiskProfileType.UNASSESSED);
        }
        if (investor.getRelationshipType() == null) {
            investor.setRelationshipType(InvestorRelationshipType.SELF);
        }
        if (investor.getAadhaarProofsAttached() == null) {
            investor.setAadhaarProofsAttached(Boolean.FALSE);
        }
        if (investor.getExternalSyncPending() == null) {
            investor.setExternalSyncPending(Boolean.FALSE);
        }
        if (investor.getIsDeleted() == null) {
            investor.setIsDeleted(Boolean.FALSE);
        }
        if (!StringUtils.hasText(investor.getFullName())) {
            investor.setFullName(StringUtils.hasText(investor.getPan()) ? "Investor " + investor.getPan() : "Investor");
        }
        if (!StringUtils.hasText(investor.getEmail())) {
            String suffix = StringUtils.hasText(investor.getPan())
                    ? investor.getPan().toLowerCase(Locale.ROOT)
                    : UUID.randomUUID().toString();
            investor.setEmail("restore+" + suffix + "@sync.platizio.local");
        }
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            investor.setMobileNumber("9000000000");
        }
    }

    private boolean shouldSkipRecentSync() {
        if (syncMinInterval.isZero() || lastSuccessfulSyncAt == null) {
            return false;
        }
        return Duration.between(lastSuccessfulSyncAt, Instant.now()).compareTo(syncMinInterval) < 0;
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizePan(String pan) {
        return pan == null ? null : pan.trim().toUpperCase(Locale.ROOT);
    }

    public record InvestorSyncResult(
            int providerCount,
            int restored,
            int imported,
            int updated,
            int skippedUnlinked,
            int skippedArchived,
            int failed,
            boolean restoreArchived,
            boolean importNew
    ) {
        public static InvestorSyncResult empty() {
            return new InvestorSyncResult(0, 0, 0, 0, 0, 0, 0, false, false);
        }
    }

    private record ReconcileOutcome(int restored, int imported, int updated, int skippedUnlinked, int skippedArchived) {
        static ReconcileOutcome none() {
            return new ReconcileOutcome(0, 0, 0, 0, 0);
        }
    }
}


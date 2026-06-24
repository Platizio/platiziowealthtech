package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.ExternalBankSyncResponse;
import com.platizio.wealthtech.dto.IfscLookupResponse;
import com.platizio.wealthtech.dto.PincodeLookupResponse;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.integration.IfscLookupResult;
import com.platizio.wealthtech.integration.PincodeLookupResult;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorOnboardingResumeResponse;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.ExternalReferenceIds;
import com.platizio.wealthtech.validation.PanFormat;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorDocumentRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvestorService implements BankVerificationStarter {

    private static final Logger logger = LoggerFactory.getLogger(InvestorService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<BankVerificationStatus> AUTO_BANK_SYNC_STATUSES = List.of(
            BankVerificationStatus.VERIFICATION_PENDING,
            BankVerificationStatus.CAPTURED
    );
    private static final List<String> REQUIRED_ONBOARDING_DOCUMENT_TYPES = List.of("PAN", "ADDRESS", "SIGNATURE");

    private final InvestorRepository investorRepository;
    private final InvestorBankAccountRepository investorBankAccountRepository;
    private final InvestorDocumentRepository investorDocumentRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;
    private final CybrillaClient cybrillaClient;
    private final ObjectProvider<InvestorKycService> investorKycServiceProvider;
    private final InvestorCybrillaSyncService investorCybrillaSyncService;
    private final long scheduledBankSyncFailureBackoffMs;
    private volatile long scheduledBankSyncBackoffUntilEpochMillis;
    private final TransactionTemplate deferredPersistTx;

    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient
    ) {
        this(investorRepository, investorBankAccountRepository, distributorService, auditService, cybrillaClient, null, 900_000, null, null, null);
    }

    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            long scheduledBankSyncFailureBackoffMs
    ) {
        this(
                investorRepository,
                investorBankAccountRepository,
                distributorService,
                auditService,
                cybrillaClient,
                null,
                scheduledBankSyncFailureBackoffMs,
                null,
                null,
                null
        );
    }

    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            InvestorCybrillaSyncService investorCybrillaSyncService,
            long scheduledBankSyncFailureBackoffMs,
            InvestorDocumentRepository investorDocumentRepository
    ) {
        this(
                investorRepository,
                investorBankAccountRepository,
                distributorService,
                auditService,
                cybrillaClient,
                investorCybrillaSyncService,
                scheduledBankSyncFailureBackoffMs,
                investorDocumentRepository,
                null,
                null
        );
    }

    @Autowired
    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            InvestorCybrillaSyncService investorCybrillaSyncService,
            @Value("${app.bank-sync.failure-backoff-ms:900000}") long scheduledBankSyncFailureBackoffMs,
            InvestorDocumentRepository investorDocumentRepository,
            PlatformTransactionManager transactionManager,
            ObjectProvider<InvestorKycService> investorKycServiceProvider
    ) {
        this.investorRepository = investorRepository;
        this.investorBankAccountRepository = investorBankAccountRepository;
        this.investorDocumentRepository = investorDocumentRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
        this.cybrillaClient = cybrillaClient;
        this.investorKycServiceProvider = investorKycServiceProvider;
        this.investorCybrillaSyncService = investorCybrillaSyncService;
        this.scheduledBankSyncFailureBackoffMs = Math.max(0, scheduledBankSyncFailureBackoffMs);
        if (transactionManager == null) {
            this.deferredPersistTx = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.deferredPersistTx = template;
        }
    }

    private void ensurePoaReadinessBeforeOrderPlacement(UUID investorId, UUID actorId) {
        if (investorKycServiceProvider == null) {
            return;
        }
        InvestorKycService investorKycService = investorKycServiceProvider.getIfAvailable();
        if (investorKycService == null) {
            return;
        }
        Investor investor = getInvestor(investorId);
        boolean sandboxCombinedPoa = investorBankAccountRepository.findByInvestorId(investorId).stream()
                .anyMatch(this::isSandboxBankVerificationPassAccount);
        if (sandboxCombinedPoa) {
            logger.info(
                    "order_placement status='skip_split_poa_readiness' investor_id='{}' reason='sandbox_combined_poa_in_bank_settle'",
                    investorId);
            return;
        }
        investorKycService.ensurePoaReadinessForOrderPlacement(investorId, actorId);
    }

    private boolean isSandboxBankVerificationPassAccount(InvestorBankAccount bankAccount) {
        // Cybrilla sandbox documents account numbers ending in 1193 as BAV-pass.
        return bankAccount != null
                && bankAccount.getAccountNumber() != null
                && bankAccount.getAccountNumber().trim().endsWith("1193");
    }

    public void syncFromCybrillaBeforeRead(UUID distributorId, boolean syncFromCybrilla, boolean forceSync) {
        if (investorCybrillaSyncService == null) {
            return;
        }
        investorCybrillaSyncService.syncBeforeRead(
                distributorId,
                investorCybrillaSyncService.usesCybrillaInvestorSource(),
                syncFromCybrilla,
                forceSync
        );
    }

    public InvestorCybrillaSyncService.InvestorSyncResult syncFromCybrillaExplicit(
            UUID distributorId,
            UUID actorId,
            boolean importNew
    ) {
        assertCanManageInvestorForDistributor(
                actorId,
                distributorId,
                "Cannot sync investors for another distributor"
        );
        if (investorCybrillaSyncService == null) {
            throw new IllegalStateException("Cybrilla investor sync is not configured");
        }
        return investorCybrillaSyncService.syncProfilesForDistributor(distributorId, true, importNew);
    }

    @Transactional
    public Investor restoreInvestorFromCybrilla(
            UUID distributorId,
            UUID actorId,
            String cybrillaInvestorId,
            String pan
    ) {
        assertCanManageInvestorForDistributor(
                actorId,
                distributorId,
                "Cannot restore investors for another distributor"
        );
        if (investorCybrillaSyncService == null) {
            throw new IllegalStateException("Cybrilla investor sync is not configured");
        }
        Investor restored = investorCybrillaSyncService.restoreProfileForDistributor(
                distributorId,
                cybrillaInvestorId,
                pan
        );
        auditService.log(
                "INVESTOR",
                restored.getId(),
                "RESTORED_FROM_CYBRILLA",
                actorId,
                "{\"cybrillaInvestorId\":\"" + restored.getCybrillaInvestorId() + "\"}"
        );
        return restored;
    }

    public InvestorCybrillaSyncService.InvestorSyncResult getLastInvestorSyncResult() {
        return investorCybrillaSyncService == null
                ? InvestorCybrillaSyncService.InvestorSyncResult.empty()
                : investorCybrillaSyncService.getLastSyncResult();
    }

    @Transactional
    public int purgeLocalInvestorCache(UUID distributorId, UUID actorId) {
        assertCanManageInvestorForDistributor(actorId, distributorId, "Cannot purge another distributor's investor cache");
        List<Investor> investors = investorRepository.findByDistributorId(distributorId);
        int purged = 0;
        for (Investor investor : investors) {
            if (Boolean.TRUE.equals(investor.getIsDeleted())) {
                continue;
            }
            investor.setIsDeleted(true);
            investor.setDeletedAt(LocalDateTime.now());
            investorRepository.save(investor);
            purged++;
        }
        auditService.log(
                "INVESTOR",
                distributorId,
                "LOCAL_CACHE_PURGED",
                actorId,
                "{\"purgedCount\":" + purged + "}"
        );
        logger.info(
                "investor_local_cache_purge status='completed' distributor_id='{}' purged='{}'",
                distributorId,
                purged
        );
        return purged;
    }

    public List<Investor> listByDistributor(UUID distributorId) {
        return investorRepository.findByDistributorId(distributorId);
    }

    @Transactional(readOnly = true)
    public List<Investor> listHousehold(UUID requesterId, UUID distributorId, UUID householdId) {
        listVisibleToDistributor(requesterId, distributorId);
        return investorRepository.findByHouseholdIdAndDistributorId(householdId, distributorId);
    }

    public List<Investor> listAll() {
        return investorRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<Investor> listVisibleToRequesterPage(UUID requesterId, UUID distributorId, int page, int size) {
        Distributor requester = distributorService.getDistributor(requesterId);
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "fullName")
        );

        if (requester.getRole() == DistributorRole.ADMIN) {
            return distributorId == null
                    ? investorRepository.findAll(pageRequest)
                    : investorRepository.findByDistributorId(distributorId, pageRequest);
        }

        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            if (distributorId != null) {
                Distributor targetDistributor = distributorService.getDistributor(distributorId);
                if (!requester.getId().equals(distributorId) && !requester.getId().equals(targetDistributor.getMasterDistributorId())) {
                    throw new AccessDeniedException("Requester cannot fetch investors for this distributor");
                }
                return investorRepository.findByDistributorId(distributorId, pageRequest);
            }

            List<UUID> distributorIds = distributorService.findSubDistributors(requesterId).stream()
                    .map(Distributor::getId)
                    .toList();
            distributorIds = new java.util.ArrayList<>(distributorIds);
            distributorIds.add(requester.getId());
            return distributorIds.isEmpty()
                    ? Page.empty(pageRequest)
                    : investorRepository.findByDistributorIdIn(distributorIds, pageRequest);
        }

        if (distributorId != null && !requester.getId().equals(distributorId)) {
            throw new AccessDeniedException("Requester cannot fetch investors for this distributor");
        }
        return investorRepository.findByDistributorId(requester.getId(), pageRequest);
    }

    public List<Investor> filterByKycStatus(String status, UUID distributorId) {
        KycStatus kycStatus = parseKycStatusFilter(status);
        if (kycStatus == null) {
            return distributorId == null ? investorRepository.findAll() : investorRepository.findByDistributorId(distributorId);
        }
        if (distributorId != null) {
            return investorRepository.findByDistributorIdAndKycStatus(distributorId, kycStatus);
        }
        return investorRepository.findByKycStatus(kycStatus);
    }

    @Transactional(readOnly = true)
    public List<Investor> filterByKycStatus(String status, UUID distributorId, UUID requesterId) {
        KycStatus kycStatus = parseKycStatusFilter(status);
        List<Investor> visibleInvestors = distributorId == null
                ? listVisibleToMaster(requesterId)
                : listVisibleToDistributor(requesterId, distributorId);
        if (kycStatus == null) {
            return visibleInvestors;
        }
        return visibleInvestors.stream()
                .filter(investor -> investor.getKycStatus() == kycStatus)
                .toList();
    }

    public List<Investor> search(String query, UUID distributorId, UUID requesterId, int limit) {
        String normalizedQuery = normalizeSearchQuery(query);
        PageRequest pageRequest = PageRequest.of(0, normalizeLimit(limit));
        if (distributorId != null) {
            if (requesterId != null && !requesterId.equals(distributorId)) {
                listVisibleToDistributor(requesterId, distributorId);
            }
            return investorRepository.searchByDistributor(distributorId, normalizedQuery, pageRequest);
        }
        if (requesterId != null) {
            Distributor requester = distributorService.getDistributor(requesterId);
            if (requester.getRole() == DistributorRole.ADMIN) {
                return investorRepository.search(normalizedQuery, pageRequest);
            }
            List<UUID> distributorIds;
            if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
                distributorIds = distributorService.findSubDistributors(requesterId).stream()
                        .map(Distributor::getId)
                        .toList();
                distributorIds = new java.util.ArrayList<>(distributorIds);
                distributorIds.add(requester.getId());
            } else {
                distributorIds = List.of(requester.getId());
            }
            return distributorIds.isEmpty()
                    ? List.of()
                    : investorRepository.searchByDistributorIds(distributorIds, normalizedQuery, pageRequest);
        }
        return investorRepository.search(normalizedQuery, pageRequest);
    }

    public List<Investor> searchEligibleForTransactions(String query, UUID distributorId, UUID requesterId, int limit) {
        return search(query, distributorId, requesterId, limit).stream()
                .filter(investor -> investor.getKycStatus() == KycStatus.COMPLETED)
                .filter(investor -> investor.getBankVerificationStatus() == BankVerificationStatus.VERIFIED)
                .toList();
    }

    public List<Investor> listVisibleToDistributor(UUID requesterId, UUID distributorId) {
        Distributor requester = distributorService.getDistributor(requesterId);
        if (requester.getRole() == DistributorRole.ADMIN || requesterId.equals(distributorId)) {
            return investorRepository.findByDistributorId(distributorId);
        }
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            Distributor targetDistributor = distributorService.getDistributor(distributorId);
            if (requesterId.equals(targetDistributor.getMasterDistributorId())) {
                return investorRepository.findByDistributorId(distributorId);
            }
        }
        throw new IllegalStateException("Requester cannot fetch investors for this distributor");
    }

    public List<Investor> listVisibleToMaster(UUID requesterId) {
        Distributor requester = distributorService.getDistributor(requesterId);
        if (requester.getRole() == DistributorRole.ADMIN) {
            return investorRepository.findAll();
        }
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            List<UUID> distributorIds = distributorService.findSubDistributors(requesterId).stream()
                    .map(Distributor::getId)
                    .toList();
            distributorIds = new java.util.ArrayList<>(distributorIds);
            distributorIds.add(requester.getId());
            return investorRepository.findByDistributorIdIn(distributorIds);
        }
        return investorRepository.findByDistributorId(requester.getId());
    }

    @Transactional
    public Investor createInvestor(InvestorCreateRequest request) {
        return createInvestor(request, null);
    }

    @Transactional
    public Investor createInvestor(InvestorCreateRequest request, UUID actorId) {
        String pan = normalizePan(request.pan());
        PanFormat.validateForDatabase(pan);
        if (request.guardianPan() != null) {
            PanFormat.validateForDatabase(normalizePan(request.guardianPan()));
        }
        String email = cleanText(request.email());

        Optional<Investor> existingByPan = investorRepository.findByPan(pan);
        if (existingByPan.isPresent()) {
            Investor existing = existingByPan.get();
            if (canResumeOnboardingDraft(existing, request.distributorId())) {
                distributorService.getDistributor(request.distributorId());
                if (actorId != null) {
                    assertCanManageInvestorForDistributor(
                            actorId,
                            request.distributorId(),
                            "Cannot create an investor for another distributor"
                    );
                }
                assertEmailAvailableForInvestor(email, existing.getId());
                return resumeOnboardingDraft(existing, request, actorId);
            }
            // BUG-001: include the existing investor id so a client can resume it.
            throw new DuplicateResourceException(
                    "An investor with this PAN already exists", existing.getId(), "pan");
        }
        // B-18: investors.email had no UNIQUE constraint; check here so the caller
        // receives a clear 409 rather than a raw DataIntegrityViolationException.
        if (email != null) {
            investorRepository.findByEmail(email).ifPresent(existing -> {
                // BUG-001: include the existing investor id so a client can resume it.
                throw new DuplicateResourceException(
                        "An investor with this email address already exists", existing.getId(), "email");
            });
        }
        // BUG-031: the active-row checks above cannot see soft-deleted investors (@SQLRestriction),
        // but the pan/email DB unique constraints are not partial. Detect a collision with a
        // soft-deleted row here so the caller gets a clean 409 (carrying the archived id) instead
        // of a raw DataIntegrityViolationException from the INSERT. Soft-delete state is unchanged.
        Optional.ofNullable(investorRepository.findAnyIdByPanIncludingDeleted(pan))
                .orElseGet(Optional::empty)
                .ifPresent(existingId -> {
                    throw new DuplicateResourceException(
                            "An investor with this PAN already exists", existingId, "pan");
                });
        if (email != null) {
            Optional.ofNullable(investorRepository.findAnyIdByEmailIncludingDeleted(email))
                    .orElseGet(Optional::empty)
                    .ifPresent(existingId -> {
                        throw new DuplicateResourceException(
                                "An investor with this email address already exists", existingId, "email");
                    });
        }
        distributorService.getDistributor(request.distributorId());
        if (actorId != null) {
            assertCanManageInvestorForDistributor(actorId, request.distributorId(), "Cannot create an investor for another distributor");
        }

        Investor investor = new Investor();
        investor.setDistributorId(request.distributorId());
        investor.setFullName(cleanText(request.fullName()));
        investor.setMobileNumber(cleanText(request.mobileNumber()));
        investor.setEmail(email);
        investor.setPan(pan);
        investor.setDateOfBirth(request.dateOfBirth());
        investor.setAnniversaryDate(request.anniversaryDate());
        investor.setGoalMaturityDate(request.goalMaturityDate());
        investor.setAddressLine1(cleanText(request.addressLine1()));
        investor.setAddressLine2(cleanText(request.addressLine2()));
        investor.setCity(cleanText(request.city()));
        investor.setState(cleanText(request.state()));
        investor.setPostalCode(cleanText(request.postalCode()));
        investor.setOnboardingNotes(cleanText(request.onboardingNotes()));
        investor.setInvestorStatus(InvestorStatus.ONBOARDING);
        investor.setKycStatus(KycStatus.PENDING);
        applyFamilyStructure(
                investor,
                request.relationshipType(),
                request.householdId(),
                request.householdName(),
                request.guardianInvestorId(),
                request.guardianPan(),
                request.distributorId()
        );

        Investor saved = investorRepository.save(investor);
        if (saved.getHouseholdId() == null && saved.getId() != null) {
            saved.setHouseholdId(saved.getId());
            saved = investorRepository.save(saved);
        }
        investorRepository.flush();

        auditService.log("INVESTOR", saved.getId(), "CREATED", request.distributorId(), "{\"pan_provided\":true}");

        try {
            String externalInvestorId = cybrillaClient.createInvestorProfile(saved);
            saved.setCybrillaInvestorId(externalInvestorId);
            try {
                String externalMfInvestmentAccountId = cybrillaClient.createMfInvestmentAccount(saved);
                saved.setExternalMfInvestmentAccountId(externalMfInvestmentAccountId);
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "Investor {} synced to FP profile but MF investment account creation failed: {}",
                        saved.getId(),
                        ex.getMessage()
                );
                saved.setExternalSyncPending(true);
                saved.setExternalSyncMessage("Investor profile was created in Fintech Primitives, but MF investment account creation is pending.");
                auditService.log(
                        "INVESTOR",
                        saved.getId(),
                        "EXTERNAL_MF_INVESTMENT_ACCOUNT_PENDING",
                        request.distributorId(),
                        "{\"externalMfInvestmentAccountPending\":true}"
                );
            }
        } catch (CybrillaApiException ex) {
            String externalMessage = investorExternalSyncMessage(ex);
            logger.warn(
                    "Investor {} saved locally with KYC pending because external profile creation failed: {}",
                    saved.getId(),
                    ex.getMessage()
            );
            saved.setKycStatus(KycStatus.PENDING);
            saved.setExternalSyncPending(true);
            saved.setExternalSyncMessage(externalMessage);
            auditService.log(
                    "INVESTOR",
                    saved.getId(),
                    "EXTERNAL_PROFILE_PENDING",
                    request.distributorId(),
                    "{\"kycStatus\":\"PENDING\",\"externalProfilePending\":true}"
            );
        }
        return investorRepository.save(saved);
    }

    @Transactional
    public Investor updateKycStatus(UUID investorId, KycStatus kycStatus, UUID actorId) {
        Investor investor = getInvestor(investorId);
        investor.setKycStatus(kycStatus);
        if (kycStatus == KycStatus.COMPLETED && investor.getBankVerificationStatus() == BankVerificationStatus.VERIFIED) {
            investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
        }
        Investor saved = investorRepository.save(investor);
        if (kycStatus == KycStatus.COMPLETED) {
            saved = startBankVerificationAfterKycCompletion(saved, actorId, "manual_kyc_status_update");
        }
        auditService.log("INVESTOR", saved.getId(), "KYC_STATUS_UPDATED", actorId, "{\"kycStatus\":\"" + kycStatus + "\"}");
        return saved;
    }

    @Transactional(readOnly = true)
    public IfscLookupResponse lookupIfsc(String ifscCode) {
        IfscLookupResult result = cybrillaClient.fetchIfscDetails(normalizeIfscCode(ifscCode));
        return new IfscLookupResponse(
                result.ifscCode(),
                result.bankName(),
                result.branchName(),
                result.branchAddress(),
                result.city(),
                result.district(),
                result.state(),
                result.micrCode()
        );
    }

    @Transactional(readOnly = true)
    public PincodeLookupResponse lookupPincode(String pincode) {
        String normalized = pincode == null ? "" : pincode.trim().replaceAll("\\D", "");
        if (!normalized.matches("\\d{6}")) {
            throw new IllegalArgumentException("Pincode must be a 6-digit number");
        }
        PincodeLookupResult result = cybrillaClient.fetchPincodeDetails(normalized);
        return new PincodeLookupResponse(
                result.code(),
                result.city(),
                result.district(),
                result.stateName(),
                result.countryAnsiCode(),
                result.cities()
        );
    }

    @Transactional
    public InvestorBankAccount addBankAccount(UUID investorId, InvestorBankRequest request, UUID actorId) {
        Investor investor = getInvestor(investorId);
        listVisibleToDistributor(actorId, investor.getDistributorId());

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        bankAccount.setInvestorId(investorId);
        bankAccount.setAccountHolderName(cleanText(request.accountHolderName()));
        bankAccount.setAccountNumber(cleanText(request.accountNumber()));
        bankAccount.setIfscCode(normalizeIfscCode(request.ifscCode()));
        bankAccount.setAccountType(normalizeBankAccountType(request.accountType()));
        bankAccount.setBankName(cleanText(request.bankName()));
        bankAccount.setBranchName(cleanText(request.branchName()));
        enrichBankDetailsFromIfsc(bankAccount);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);

        InvestorBankAccount savedBank = investorBankAccountRepository.save(bankAccount);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investorRepository.save(investor);

        savedBank = syncBankVerificationStatus(investor, savedBank, actorId, "bank_account_created");
        auditService.log("INVESTOR_BANK", savedBank.getId(), "BANK_ADDED", actorId, "{\"investorId\":\"" + investorId + "\"}");
        return savedBank;
    }

    @Transactional(readOnly = true)
    public List<InvestorBankAccount> listBankAccounts(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        listVisibleToDistributor(actorId, investor.getDistributorId());
        return investorBankAccountRepository.findByInvestorId(investorId);
    }

    @Transactional
    public InvestorBankAccount refreshBankVerification(UUID investorId, UUID bankAccountId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        listVisibleToDistributor(actorId, investor.getDistributorId());
        InvestorBankAccount bankAccount = investorBankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new EntityNotFoundException("Bank account not found"));
        if (!investorId.equals(bankAccount.getInvestorId())) {
            throw new AccessDeniedException("Bank account does not belong to this investor");
        }
        InvestorBankAccount savedBank = syncBankVerificationStatus(investor, bankAccount, actorId, "manual");
        auditService.log(
                "INVESTOR_BANK",
                savedBank.getId(),
                "BANK_VERIFICATION_REFRESHED",
                actorId,
                "{\"investorId\":\"" + investorId + "\",\"status\":\"" + savedBank.getVerificationStatus() + "\"}"
        );
        return savedBank;
    }

    @Transactional
    public ExternalBankSyncResponse handleBankPreVerificationWebhook(JsonNode payload) {
        JsonNode externalObject = webhookDataObject(payload);
        String eventType = textOrNull(payload, "type");
        String externalId = textOrNull(externalObject, "id");
        if (!StringUtils.hasText(externalId)) {
            return new ExternalBankSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<InvestorBankAccount> bankAccount = investorBankAccountRepository.findByCybrillaBankVerificationId(externalId.trim());
        if (bankAccount.isEmpty()) {
            return new ExternalBankSyncResponse("ignored_no_matching_bank_account", eventType, externalId, null, null);
        }
        Investor investor = getInvestor(bankAccount.get().getInvestorId());
        InvestorBankAccount synced = syncBankVerificationStatus(
                investor,
                bankAccount.get(),
                investor.getDistributorId(),
                "bank_pre_verification_webhook"
        );
        auditService.log(
                "INVESTOR_BANK",
                synced.getId(),
                "EXTERNAL_BANK_WEBHOOK_SYNCED",
                investor.getDistributorId(),
                "{\"investorId\":\"" + investor.getId() + "\",\"status\":\"" + synced.getVerificationStatus() + "\"}"
        );
        return new ExternalBankSyncResponse(
                "synced",
                eventType,
                externalId,
                investor.getId(),
                synced.getVerificationStatus()
        );
    }

    @Transactional
    public int syncOutstandingBankVerificationStatuses(int limit) {
        long now = System.currentTimeMillis();
        long backoffUntil = scheduledBankSyncBackoffUntilEpochMillis;
        if (now < backoffUntil) {
            logger.debug(
                    "external_bank_verification_sync status='skipped' reason='provider_backoff' retry_in_ms='{}'",
                    backoffUntil - now
            );
            return 0;
        }

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<InvestorBankAccount> candidates = investorBankAccountRepository.findBankVerificationSyncCandidates(
                AUTO_BANK_SYNC_STATUSES,
                PageRequest.of(0, safeLimit)
        );
        int synced = 0;
        for (InvestorBankAccount bankAccount : candidates) {
            try {
                Investor investor = getInvestor(bankAccount.getInvestorId());
                InvestorBankAccount syncedBank = syncBankVerificationStatus(
                        investor,
                        bankAccount,
                        investor.getDistributorId(),
                        "scheduled"
                );
                synced++;
                if (isExternalBankSyncBlocked(syncedBank)) {
                    activateScheduledBankSyncBackoff(syncedBank.getExternalSyncMessage(), now);
                    break;
                }
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "external_bank_verification_sync status='failed' trigger='scheduled' bank_account_id='{}' reason='{}'",
                        bankAccount.getId(),
                        ex.getMessage()
                );
                activateScheduledBankSyncBackoff(ex.getMessage(), now);
                break;
            } catch (RuntimeException ex) {
                logger.warn(
                        "external_bank_verification_sync status='failed' trigger='scheduled' bank_account_id='{}' reason='{}'",
                        bankAccount.getId(),
                        ex.getMessage()
                );
            }
        }
        return synced;
    }

    @Transactional
    public Investor startBankVerificationAfterKycCompletion(Investor investor, UUID actorId, String trigger) {
        if (investor == null || investor.getKycStatus() != KycStatus.COMPLETED || investor.getId() == null) {
            return investor;
        }

        investor = ensureExternalInvestorReadyAfterKycCompletion(investor, actorId, trigger);
        if (!hasResolvableCybrillaInvestorId(investor)) {
            return investor;
        }

        List<InvestorBankAccount> bankAccounts = investorBankAccountRepository.findByInvestorId(investor.getId());
        if (bankAccounts.isEmpty()) {
            return investor;
        }

        for (InvestorBankAccount bankAccount : bankAccounts) {
            if (bankAccount.getVerificationStatus() == BankVerificationStatus.VERIFIED) {
                continue;
            }
            try {
                syncBankVerificationStatus(investor, bankAccount, actorId, trigger);
            } catch (CybrillaApiException ex) {
                markBankVerificationPending(investor, bankAccount, bankExternalSyncMessage(ex), actorId);
                logger.warn(
                        "external_bank_verification_sync status='failed' trigger='{}' bank_account_id='{}' reason='{}'",
                        trigger,
                        bankAccount.getId(),
                        ex.getMessage()
                );
                break;
            }
        }
        return investorRepository.save(investor);
    }

    private Investor ensureExternalInvestorReadyAfterKycCompletion(Investor investor, UUID actorId, String trigger) {
        Investor saved = investor;
        clearPlaceholderExternalReferences(saved);
        if (!hasResolvableCybrillaInvestorId(saved)) {
            try {
                String externalInvestorId = cybrillaClient.createInvestorProfile(saved);
                if (!hasText(externalInvestorId)) {
                    throw new CybrillaApiException("Fintech Primitives investor profile response did not include id");
                }
                saved.setCybrillaInvestorId(externalInvestorId);
                saved.setExternalSyncPending(Boolean.FALSE);
                saved.setExternalSyncMessage(null);
                saved = investorRepository.save(saved);
                auditService.log(
                        "INVESTOR",
                        saved.getId(),
                        "EXTERNAL_PROFILE_CREATED_AFTER_KYC",
                        actorId,
                        "{\"trigger\":\"" + trigger + "\",\"externalProfileCreated\":true}"
                );
                logger.info(
                        "external_investor_profile_sync status='completed' trigger='{}' investor_id='{}' external_profile_id='{}'",
                        trigger,
                        saved.getId(),
                        saved.getCybrillaInvestorId()
                );
            } catch (CybrillaApiException ex) {
                saved.setExternalSyncPending(Boolean.TRUE);
                saved.setExternalSyncMessage(completedKycExternalProfileMessage(ex));
                saved = investorRepository.save(saved);
                auditService.log(
                        "INVESTOR",
                        saved.getId(),
                        "EXTERNAL_PROFILE_PENDING_AFTER_KYC",
                        actorId,
                        "{\"trigger\":\"" + trigger + "\",\"externalProfilePending\":true}"
                );
                logger.warn(
                        "external_investor_profile_sync status='failed' trigger='{}' investor_id='{}' reason='{}'",
                        trigger,
                        saved.getId(),
                        ex.getMessage()
                );
                return saved;
            }
        }

        if (!hasResolvableMfInvestmentAccountId(saved)) {
            try {
                String externalMfInvestmentAccountId = cybrillaClient.createMfInvestmentAccount(saved);
                if (!hasText(externalMfInvestmentAccountId)) {
                    throw new CybrillaApiException("Fintech Primitives MF investment account response did not include id");
                }
                saved.setExternalMfInvestmentAccountId(externalMfInvestmentAccountId);
                saved.setExternalSyncPending(Boolean.FALSE);
                saved.setExternalSyncMessage(null);
                saved = investorRepository.save(saved);
                auditService.log(
                        "INVESTOR",
                        saved.getId(),
                        "EXTERNAL_MF_INVESTMENT_ACCOUNT_CREATED_AFTER_KYC",
                        actorId,
                        "{\"trigger\":\"" + trigger + "\",\"externalMfInvestmentAccountCreated\":true}"
                );
            } catch (CybrillaApiException ex) {
                saved.setExternalSyncPending(Boolean.TRUE);
                saved.setExternalSyncMessage("KYC is complete and the investor profile exists in Fintech Primitives, but MF investment account creation is pending.");
                saved = investorRepository.save(saved);
                auditService.log(
                        "INVESTOR",
                        saved.getId(),
                        "EXTERNAL_MF_INVESTMENT_ACCOUNT_PENDING_AFTER_KYC",
                        actorId,
                        "{\"trigger\":\"" + trigger + "\",\"externalMfInvestmentAccountPending\":true}"
                );
                logger.warn(
                        "external_mf_investment_account_sync status='failed' trigger='{}' investor_id='{}' reason='{}'",
                        trigger,
                        saved.getId(),
                        ex.getMessage()
                );
            }
        }

        return saved;
    }

    private boolean isExternalBankSyncBlocked(InvestorBankAccount bankAccount) {
        return Boolean.TRUE.equals(bankAccount.getExternalSyncPending());
    }

    private void activateScheduledBankSyncBackoff(String reason, long failureEpochMillis) {
        if (scheduledBankSyncFailureBackoffMs <= 0) {
            return;
        }
        long backoffUntil = failureEpochMillis + scheduledBankSyncFailureBackoffMs;
        scheduledBankSyncBackoffUntilEpochMillis = Math.max(scheduledBankSyncBackoffUntilEpochMillis, backoffUntil);
        logger.warn(
                "external_bank_verification_sync status='backing_off' retry_after_ms='{}' reason='{}'",
                scheduledBankSyncFailureBackoffMs,
                reason
        );
    }

    private InvestorBankAccount syncBankVerificationStatus(
            Investor investor,
            InvestorBankAccount bankAccount,
            UUID actorId,
            String trigger
    ) {
        if (investor.getKycStatus() != KycStatus.COMPLETED) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            bankAccount.setExternalSyncPending(false);
            bankAccount.setExternalSyncMessage("Bank verification will start automatically after KYC is completed.");
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investorRepository.save(investor);
            return investorBankAccountRepository.save(bankAccount);
        }

        investor = ensureExternalInvestorReadyAfterKycCompletion(investor, actorId, trigger);
        if (!hasResolvableCybrillaInvestorId(investor)) {
            return markBankVerificationPending(
                    investor,
                    bankAccount,
                    "Unable to post bank data to Cybrilla/Fintech Primitives because the investor profile is still pending. Bank details were saved locally for retry.",
                    actorId
            );
        }

        if (bankAccount.getCybrillaBankVerificationId() == null || bankAccount.getCybrillaBankVerificationId().isBlank()) {
            try {
                if (hasText(bankAccount.getCybrillaBankId())) {
                    cybrillaClient.startBankAccountVerification(investor, bankAccount);
                } else {
                    cybrillaClient.captureBankAccount(investor, bankAccount);
                }
                if (bankAccount.getCybrillaBankVerificationId() != null && !bankAccount.getCybrillaBankVerificationId().isBlank()) {
                    bankAccount.setExternalSyncPending(false);
                    bankAccount.setExternalSyncMessage(null);
                } else if (!Boolean.TRUE.equals(bankAccount.getExternalSyncPending())) {
                    bankAccount.setExternalSyncPending(true);
                    bankAccount.setExternalSyncMessage("Bank account was captured in Fintech Primitives, but bank verification could not be started. Please confirm bank verification is enabled for this tenant.");
                }
            } catch (CybrillaApiException ex) {
                String externalMessage = bankExternalSyncMessage(ex);
                return markBankVerificationPending(investor, bankAccount, externalMessage, actorId);
            }
            if (bankAccount.getCybrillaBankVerificationId() == null || bankAccount.getCybrillaBankVerificationId().isBlank()) {
                bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
                investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
                investorRepository.save(investor);
                return investorBankAccountRepository.save(bankAccount);
            }
        }

        JsonNode verification;
        try {
            verification = pollBankAccountVerificationUntilSettled(bankAccount, trigger);
            bankAccount.setExternalVerificationResponseJson(writeJson(verification));
            applyBankVerificationResponse(investor, bankAccount, verification);
        } catch (CybrillaApiException ex) {
            return markBankVerificationPending(investor, bankAccount, bankExternalSyncMessage(ex), actorId);
        }

        if (bankAccount.getVerificationStatus() != BankVerificationStatus.VERIFIED
                && shouldRestartBankVerification(bankAccount, trigger)) {
            logger.info(
                    "external_bank_verification_sync status='retrying' trigger='{}' investor_id='{}' bank_account_id='{}' reason='{}'",
                    trigger,
                    investor.getId(),
                    bankAccount.getId(),
                    bankAccount.getExternalSyncMessage()
            );
            bankAccount.setCybrillaBankVerificationId(null);
            bankAccount.setCybrillaBankVerificationStatus(null);
            bankAccount.setCybrillaBankVerificationConfidence(null);
            bankAccount.setExternalSyncMessage(null);
            try {
                if (hasText(bankAccount.getCybrillaBankId())) {
                    cybrillaClient.startBankAccountVerification(investor, bankAccount);
                } else {
                    cybrillaClient.captureBankAccount(investor, bankAccount);
                }
            } catch (CybrillaApiException ex) {
                return markBankVerificationPending(investor, bankAccount, bankExternalSyncMessage(ex), actorId);
            }
            if (!hasText(bankAccount.getCybrillaBankVerificationId())) {
                bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
                investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
                investorRepository.save(investor);
                return investorBankAccountRepository.save(bankAccount);
            }
            try {
                verification = pollBankAccountVerificationUntilSettled(bankAccount, trigger);
                bankAccount.setExternalVerificationResponseJson(writeJson(verification));
                applyBankVerificationResponse(investor, bankAccount, verification);
            } catch (CybrillaApiException ex) {
                return markBankVerificationPending(investor, bankAccount, bankExternalSyncMessage(ex), actorId);
            }
        }

        InvestorBankAccount savedBank = investorBankAccountRepository.save(bankAccount);
        investorRepository.save(investor);
        logger.info(
                "external_bank_verification_sync status='completed' trigger='{}' investor_id='{}' bank_account_id='{}' bank_status='{}'",
                trigger,
                investor.getId(),
                savedBank.getId(),
                savedBank.getVerificationStatus()
        );
        return savedBank;
    }

    private static final int ORDER_PLACEMENT_BANK_VERIFICATION_POLL_ATTEMPTS = 15;
    private static final int SANDBOX_ORDER_PLACEMENT_BANK_VERIFICATION_POLL_ATTEMPTS = 20;
    private static final long ORDER_PLACEMENT_BANK_VERIFICATION_POLL_INTERVAL_MS = 2_000L;
    private static final long SANDBOX_ORDER_PLACEMENT_BANK_VERIFICATION_POLL_INTERVAL_MS = 1_000L;

    private JsonNode pollBankAccountVerificationUntilSettled(InvestorBankAccount bankAccount, String trigger) {
        boolean sandboxPassAccount = isSandboxBankVerificationPassAccount(bankAccount);
        int maxAttempts = "order_placement".equals(trigger)
                ? (sandboxPassAccount
                        ? SANDBOX_ORDER_PLACEMENT_BANK_VERIFICATION_POLL_ATTEMPTS
                        : ORDER_PLACEMENT_BANK_VERIFICATION_POLL_ATTEMPTS)
                : 1;
        JsonNode verification = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Map<String, Object> requestSnapshot = new LinkedHashMap<>();
            verification = cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(
                    bankAccount.getCybrillaBankVerificationId(),
                    requestSnapshot
            );
            bankAccount.setExternalVerificationRequestJson(writeJson(requestSnapshot));
            if (isBankVerificationPollTerminal(verification, sandboxPassAccount && "order_placement".equals(trigger))) {
                return verification;
            }
            if (attempt < maxAttempts) {
                sleepOrderPlacementBankPollInterval(sandboxPassAccount);
            }
        }
        return verification;
    }

    private boolean isBankVerificationPollTerminal(JsonNode verification, boolean requireCombinedPoaReady) {
        if (verification == null || verification.isNull()) {
            return false;
        }
        if ("pre_verification".equalsIgnoreCase(textOrNull(verification, "object"))) {
            String topLevelStatus = textOrNull(verification, "status");
            if ("failed".equalsIgnoreCase(topLevelStatus)) {
                return true;
            }
            if (requireCombinedPoaReady) {
                return isCombinedPoaVerificationReadyForOrder(verification)
                        || isCombinedPoaVerificationFailed(verification);
            }
            if (!"completed".equalsIgnoreCase(topLevelStatus)) {
                return false;
            }
            JsonNode bankVerification = firstBankAccountResult(verification);
            String bankStatus = textOrNull(bankVerification, "status");
            return "verified".equalsIgnoreCase(bankStatus) || "failed".equalsIgnoreCase(bankStatus);
        }
        String topLevelStatus = textOrNull(verification, "status");
        if ("completed".equalsIgnoreCase(topLevelStatus) || "failed".equalsIgnoreCase(topLevelStatus)) {
            return true;
        }
        return false;
    }

    private boolean isCombinedPoaVerificationFailed(JsonNode verification) {
        if (verification == null || verification.isNull()) {
            return false;
        }
        if ("failed".equalsIgnoreCase(textOrNull(verification, "status"))) {
            return true;
        }
        if ("failed".equalsIgnoreCase(nestedText(verification, "readiness", "status"))) {
            return true;
        }
        if ("failed".equalsIgnoreCase(nestedText(verification, "pan", "status"))) {
            return true;
        }
        JsonNode bankAccounts = verification.path("bank_accounts");
        if (bankAccounts.isArray() && !bankAccounts.isEmpty()) {
            return "failed".equalsIgnoreCase(bankAccounts.get(0).path("status").asText(""));
        }
        return false;
    }

    private boolean isBankVerificationPollTerminal(JsonNode verification) {
        return isBankVerificationPollTerminal(verification, false);
    }

    private void sleepOrderPlacementBankPollInterval(boolean sandboxPassAccount) {
        try {
            Thread.sleep(sandboxPassAccount
                    ? SANDBOX_ORDER_PLACEMENT_BANK_VERIFICATION_POLL_INTERVAL_MS
                    : ORDER_PLACEMENT_BANK_VERIFICATION_POLL_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sandbox accounts ending in {@code 1193} must still complete POA BAV before ONDC purchase review.
     * Local {@code VERIFIED} alone causes {@code investor_data_submission_error} on mf_purchase review.
     */
    private InvestorBankAccount ensureSandboxBankVerificationSettledForOrder(
            Investor investor,
            InvestorBankAccount bankAccount,
            UUID actorId
    ) {
        logger.info(
                "order_placement status='sandbox_bank_poa_settle' investor_id='{}' bank_account_id='{}'",
                investor.getId(),
                bankAccount.getId());
        investor = ensureExternalInvestorReadyAfterKycCompletion(investor, actorId, "order_placement");
        if (hasText(bankAccount.getCybrillaBankId()) && !fpBankAccountMatchesLocal(bankAccount)) {
            logger.warn(
                    "order_placement status='clear_stale_fp_bank' investor_id='{}' bank_account_id='{}' external_bank_id='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankId());
            clearFpBankAccountLinkage(bankAccount);
        }
        if (hasText(bankAccount.getCybrillaBankVerificationId()) && poaBankVerificationPanMismatch(investor, bankAccount)) {
            logger.warn(
                    "order_placement status='clear_stale_poa_bav' investor_id='{}' bank_account_id='{}' external_verification_id='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankVerificationId());
            clearFpBankAccountLinkage(bankAccount);
        }
        if (hasText(bankAccount.getCybrillaBankVerificationId()) && poaBankVerificationMissingInvestorIdentifier(bankAccount)) {
            logger.warn(
                    "order_placement status='clear_poa_bav_missing_identifier' investor_id='{}' bank_account_id='{}' external_verification_id='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankVerificationId());
            clearFpBankAccountLinkage(bankAccount);
        }
        if (!hasText(bankAccount.getCybrillaBankId())) {
            cybrillaClient.ensureFpBankAccountCaptured(investor, bankAccount);
        }
        clearPoaBankVerificationOnly(bankAccount);
        investor.setExternalKycCheckId(null);
        JsonNode verification = pollCombinedOrderPreVerification(investor, bankAccount);
        bankAccount.setExternalVerificationResponseJson(writeJson(verification));
        applyPoaReadinessFieldsFromVerification(investor, verification);
        applyBankVerificationResponse(investor, bankAccount, verification);
        if (!isCombinedPoaVerificationReadyForOrder(verification)) {
            logger.warn(
                    "order_placement status='combined_poa_not_ready' investor_id='{}' bank_account_id='{}' pv_id='{}' top_status='{}' readiness='{}' pan='{}' bank='{}'",
                    investor.getId(),
                    bankAccount.getId(),
                    textOrNull(verification, "id"),
                    textOrNull(verification, "status"),
                    nestedText(verification, "readiness", "status"),
                    nestedText(verification, "pan", "status"),
                    verification == null || !verification.path("bank_accounts").isArray() || verification.path("bank_accounts").isEmpty()
                            ? null
                            : verification.path("bank_accounts").get(0).path("status").asText(null));
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investorRepository.save(investor);
            return investorBankAccountRepository.save(bankAccount);
        }
        logger.info(
                "order_placement status='combined_poa_ready' investor_id='{}' bank_account_id='{}' pv_id='{}' external_bank_id='{}'",
                investor.getId(),
                bankAccount.getId(),
                textOrNull(verification, "id"),
                bankAccount.getCybrillaBankId());
        investorRepository.save(investor);
        return investorBankAccountRepository.save(bankAccount);
    }

    private JsonNode pollCombinedOrderPreVerification(Investor investor, InvestorBankAccount bankAccount) {
        JsonNode created = cybrillaClient.createCombinedOrderPreVerification(investor, bankAccount);
        String verificationId = textOrNull(created, "id");
        if (hasText(verificationId)) {
            bankAccount.setCybrillaBankVerificationId(verificationId);
        }
        return pollBankAccountVerificationUntilSettled(bankAccount, "order_placement");
    }

    private void applyPoaReadinessFieldsFromVerification(Investor investor, JsonNode verification) {
        if (verification == null || verification.isNull()) {
            return;
        }
        String verificationId = textOrNull(verification, "id");
        if (hasText(verificationId)) {
            investor.setExternalKycCheckId(verificationId);
        }
        investor.setKycReadinessStatus(nestedText(verification, "readiness", "status"));
        investor.setKycReadinessCode(nestedText(verification, "readiness", "code"));
        investor.setKycReadinessReason(nestedText(verification, "readiness", "reason"));
        investor.setPanVerificationStatus(nestedText(verification, "pan", "status"));
        investor.setPanVerificationCode(nestedText(verification, "pan", "code"));
        investor.setPanVerificationReason(nestedText(verification, "pan", "reason"));
        investor.setExternalKycStatus(textOrNull(verification, "status"));
        investor.setExternalKycPayloadJson(writeJson(verification));
    }

    private boolean isCombinedPoaVerificationReadyForOrder(JsonNode verification) {
        if (verification == null || verification.isNull()) {
            return false;
        }
        if (!"completed".equalsIgnoreCase(textOrNull(verification, "status"))) {
            return false;
        }
        if (!"verified".equalsIgnoreCase(nestedText(verification, "readiness", "status"))) {
            return false;
        }
        if (!"verified".equalsIgnoreCase(nestedText(verification, "pan", "status"))) {
            return false;
        }
        JsonNode bankAccounts = verification.path("bank_accounts");
        if (!bankAccounts.isArray() || bankAccounts.isEmpty()) {
            return false;
        }
        return "verified".equalsIgnoreCase(bankAccounts.get(0).path("status").asText(""));
    }

    private static String nestedText(JsonNode node, String objectName, String fieldName) {
        JsonNode nested = node == null ? null : node.path(objectName).path(fieldName);
        if (nested.isMissingNode() || nested.isNull()) {
            return null;
        }
        return nested.asText(null);
    }

    private boolean shouldRestartBankVerification(InvestorBankAccount bankAccount, String trigger) {
        if (!"order_placement".equals(trigger)) {
            return false;
        }
        if (bankAccount.getVerificationStatus() == BankVerificationStatus.VERIFIED) {
            return false;
        }
        return isBankRetryableCode(bankAccount.getCybrillaBankVerificationConfidence())
                || (bankAccount.getVerificationStatus() == BankVerificationStatus.VERIFICATION_PENDING
                && StringUtils.hasText(bankAccount.getExternalSyncMessage())
                && bankAccount.getExternalSyncMessage().toLowerCase(Locale.ROOT).contains("retry"));
    }

    private InvestorBankAccount markBankVerificationPending(
            Investor investor,
            InvestorBankAccount bankAccount,
            String externalMessage,
            UUID actorId
    ) {
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        bankAccount.setExternalSyncPending(true);
        bankAccount.setExternalSyncMessage(externalMessage);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investorRepository.save(investor);
        InvestorBankAccount savedBank = investorBankAccountRepository.save(bankAccount);
        auditService.log(
                "INVESTOR_BANK",
                savedBank.getId(),
                "EXTERNAL_BANK_SYNC_PENDING",
                actorId,
                "{\"investorId\":\"" + investor.getId() + "\",\"externalBankSyncPending\":true}"
        );
        return savedBank;
    }

    @Transactional
    public Investor verifyBank(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
        if (investor.getKycStatus() == KycStatus.COMPLETED) {
            investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
        }
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "BANK_VERIFIED", actorId, "{}");
        return saved;
    }

    public Investor getInvestor(UUID investorId) {
        return investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
    }

    @Transactional(readOnly = true)
    public Investor getInvestor(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        assertCanManageInvestor(actorId, investor, "Cannot access another distributor's investor");
        return investor;
    }

    /**
     * Marks an investor READY_FOR_TRANSACTIONS after the investor-approval gate has
     * passed (SRS FR-ONB-003). Additive — the caller (InvestorController.finalize)
     * is responsible for first asserting the onboarding submission is finalizable.
     */
    @Transactional
    public Investor markReadyAfterInvestorApproval(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId, actorId);
        investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "ONBOARDING_FINALIZED", actorId, "{}");
        return saved;
    }

    @Transactional(readOnly = true)
    public InvestorOnboardingResumeResponse getOnboardingResume(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId, actorId);
        List<InvestorBankAccount> bankAccounts = investorBankAccountRepository.findByInvestorId(investorId);
        boolean hasExternalKycReference = hasText(investor.getExternalKycCheckId()) || hasText(investor.getExternalKycRequestId());
        boolean hasBankAccount = !bankAccounts.isEmpty();
        boolean documentsComplete = onboardingDocumentsComplete(investorId);
        List<String> missingDocumentTypes = missingOnboardingDocumentTypes(investorId);
        boolean readyForTransactions = investor.getKycStatus() == KycStatus.COMPLETED
                && investor.getBankVerificationStatus() == BankVerificationStatus.VERIFIED
                && documentsComplete;
        boolean canRefreshBankVerification = bankAccounts.stream()
                .anyMatch(bank -> bank.getVerificationStatus() != BankVerificationStatus.VERIFIED);

        String nextStep;
        String message;
        if (Boolean.TRUE.equals(investor.getExternalSyncPending()) && !hasText(investor.getCybrillaInvestorId())) {
            nextStep = "SYNC_INVESTOR_PROFILE";
            message = textOrDefault(
                    investor.getExternalSyncMessage(),
                    "Investor is saved locally, but the Fintech Primitives profile still needs to be synced."
            );
        } else if (investor.getKycStatus() == KycStatus.IN_PROGRESS) {
            nextStep = "REFRESH_KYC";
            message = "KYC is already started. Refresh to fetch the latest Cybrilla status.";
        } else if (investor.getKycStatus() != KycStatus.COMPLETED) {
            nextStep = "APPLY_KYC";
            message = "Continue by applying KYC through the backend Cybrilla integration.";
        } else if (!hasBankAccount) {
            nextStep = "ADD_BANK_ACCOUNT";
            message = "KYC is complete. Add a bank account to continue onboarding.";
        } else if (investor.getBankVerificationStatus() != BankVerificationStatus.VERIFIED) {
            nextStep = "REFRESH_BANK_VERIFICATION";
            message = "Bank details are present. Refresh bank verification to fetch the latest Cybrilla status.";
        } else if (!documentsComplete) {
            nextStep = "UPLOAD_DOCUMENTS";
            message = "Bank verification is complete. Upload PAN, address proof, and signature to finish onboarding.";
        } else {
            nextStep = "READY_FOR_TRANSACTIONS";
            message = "Investor onboarding is complete.";
        }

        return new InvestorOnboardingResumeResponse(
                investor,
                bankAccounts,
                nextStep,
                investor.getKycStatus() != KycStatus.IN_PROGRESS && investor.getKycStatus() != KycStatus.COMPLETED,
                hasExternalKycReference,
                investor.getKycStatus() == KycStatus.COMPLETED
                        || investor.getKycStatus() == KycStatus.FAILED
                        || investor.getKycStatus() == KycStatus.RETRY_REQUIRED
                        || hasExternalKycReference,
                investor.getKycStatus() == KycStatus.COMPLETED,
                canRefreshBankVerification,
                documentsComplete,
                missingDocumentTypes,
                readyForTransactions,
                message
        );
    }

    private boolean onboardingDocumentsComplete(UUID investorId) {
        return missingOnboardingDocumentTypes(investorId).isEmpty();
    }

    private List<String> missingOnboardingDocumentTypes(UUID investorId) {
        if (investorDocumentRepository == null) {
            return List.of();
        }
        Set<String> presentTypes = investorDocumentRepository.findAllByInvestorIdOrderByDocumentTypeAsc(investorId).stream()
                .map(InvestorDocument::getDocumentType)
                .filter(this::hasText)
                .map(type -> type.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return REQUIRED_ONBOARDING_DOCUMENT_TYPES.stream()
                .filter(type -> !presentTypes.contains(type))
                .toList();
    }

    @Transactional
    public Investor ensureMfInvestmentAccount(UUID investorId) {
        Investor investor = getInvestor(investorId);
        clearPlaceholderExternalReferences(investor);
        boolean mfAccountLinkedAtEntry = hasResolvableCybrillaInvestorId(investor)
                && hasResolvableMfInvestmentAccountId(investor)
                && mfInvestmentAccountMatchesProfile(investor);
        if (investor.getKycStatus() != KycStatus.COMPLETED) {
            throw new IllegalStateException("KYC must be completed before opening an MF investment account");
        }
        ensurePoaReadinessBeforeOrderPlacement(investorId, investor.getDistributorId());
        investor = getInvestor(investorId);
        investor = ensureExternalInvestorReadyAfterKycCompletion(investor, investor.getDistributorId(), "order_placement");
        investorRepository.flush();
        investor = getInvestor(investorId);
        clearPlaceholderExternalReferences(investor);
        if (!hasResolvableCybrillaInvestorId(investor)) {
            String message = investor.getExternalSyncMessage();
            String errorMessage = StringUtils.hasText(message)
                    ? message
                    : "Fintech Primitives investor profile is required before opening an MF investment account. "
                            + "Complete investor onboarding sync (POST /investors/sync-from-cybrilla) or pick a demo-ready investor such as Anita Verma.";
            markExternalProfileSyncDeferred(investorId, errorMessage);
            throw new IllegalStateException(errorMessage);
        }
        if (hasResolvableMfInvestmentAccountId(investor) && !mfInvestmentAccountMatchesProfile(investor)) {
            investor.setExternalMfInvestmentAccountId(null);
            clearPlaceholderExternalReferences(investor);
        }
        if (!hasResolvableMfInvestmentAccountId(investor)) {
            try {
                String externalMfInvestmentAccountId = cybrillaClient.createMfInvestmentAccount(investor);
                investor.setExternalMfInvestmentAccountId(externalMfInvestmentAccountId);
                investor.setExternalSyncPending(Boolean.FALSE);
                investor.setExternalSyncMessage(null);
                investor = investorRepository.save(investor);
            } catch (CybrillaApiException ex) {
                String errorMessage = "Unable to open the Fintech Primitives MF investment account: " + ex.getMessage();
                markExternalProfileSyncDeferred(investorId, errorMessage);
                // BUG-024: surface provider failures as 502/503, not 400. Rethrow the
                // original CybrillaApiException (preserves the CybrillaUnavailableException
                // subtype) so GlobalExceptionHandler maps it correctly.
                throw ex;
            }
        }
        if (mfAccountLinkedAtEntry) {
            logger.info(
                    "mf_investment_account_prepare status='skipped_already_linked' investor_id='{}' external_profile_id='{}' external_mf_investment_account_id='{}' action='skip_order_ready'",
                    investor.getId(),
                    investor.getCybrillaInvestorId(),
                    investor.getExternalMfInvestmentAccountId()
            );
        }

        InvestorBankAccount verifiedBank = investorBankAccountRepository.findByInvestorId(investorId).stream()
                .filter(account -> account.getVerificationStatus() == BankVerificationStatus.VERIFIED
                        || isSandboxBankVerificationPassAccount(account))
                .findFirst()
                .orElse(null);
        if (verifiedBank == null) {
            throw new IllegalStateException("A verified bank account is required before placing orders.");
        }
        try {
            if (isSandboxBankVerificationPassAccount(verifiedBank)) {
                verifiedBank = ensureSandboxBankVerificationSettledForOrder(
                        investor,
                        verifiedBank,
                        investor.getDistributorId());
                investor = getInvestor(investorId);
                if (verifiedBank.getVerificationStatus() != BankVerificationStatus.VERIFIED) {
                    String detail = StringUtils.hasText(verifiedBank.getExternalSyncMessage())
                            ? verifiedBank.getExternalSyncMessage()
                            : "POA bank pre-verification did not reach verified for sandbox account ending in 1193.";
                    throw new IllegalStateException(
                            "Bank account must be verified with Fintech Primitives before placing orders: " + detail);
                }
            } else {
                verifiedBank = syncBankVerificationStatus(investor, verifiedBank, investor.getDistributorId(), "order_placement");
                investor = getInvestor(investorId);
                if (verifiedBank.getVerificationStatus() != BankVerificationStatus.VERIFIED) {
                    String detail = StringUtils.hasText(verifiedBank.getExternalSyncMessage())
                            ? verifiedBank.getExternalSyncMessage()
                            : "Fintech Primitives bank verification is still in progress.";
                    throw new IllegalStateException(
                            "Bank account must be verified with Fintech Primitives before placing orders: " + detail);
                }
            }
            investor = getInvestor(investorId);
            // BUG-012: when invp_+mfia_ are already linked at entry, the order-ready
            // PATCH is redundant and re-triggers the FP occupation-immutability problem.
            // Skip only the PATCH calls; the bank-verification sync above still runs.
            if (!mfAccountLinkedAtEntry) {
                if (hasResolvableCybrillaInvestorId(investor)) {
                    cybrillaClient.ensureInvestorProfileOrderReady(investor);
                }
                cybrillaClient.ensureMfInvestmentAccountOrderReady(investor, verifiedBank);
            }
            investorBankAccountRepository.save(verifiedBank);
        } catch (CybrillaApiException ex) {
            String errorMessage = "Unable to prepare the Fintech Primitives MF investment account for orders: " + ex.getMessage();
            markExternalProfileSyncDeferred(investorId, errorMessage);
            // BUG-024: surface provider failures as 502/503, not 400. Rethrow the
            // original CybrillaApiException (preserves the CybrillaUnavailableException
            // subtype) so GlobalExceptionHandler maps it correctly.
            throw ex;
        }
        return investor;
    }

    /**
     * Re-syncs FP profile, POA bank verification, and MFIA folio defaults after a failed purchase review.
     */
    @Transactional
    public Investor repairInvestorForFpOrders(UUID investorId) {
        Investor investor = getInvestor(investorId);
        InvestorBankAccount verifiedBank = investorBankAccountRepository.findByInvestorId(investorId).stream()
                .filter(account -> account.getVerificationStatus() == BankVerificationStatus.VERIFIED
                        || isSandboxBankVerificationPassAccount(account))
                .findFirst()
                .orElse(null);
        if (verifiedBank != null) {
            if (hasText(verifiedBank.getCybrillaBankId()) && !fpBankAccountMatchesLocal(verifiedBank)) {
                clearFpBankAccountLinkage(verifiedBank);
            }
            if (hasText(verifiedBank.getCybrillaBankVerificationId()) && poaBankVerificationPanMismatch(investor, verifiedBank)) {
                clearFpBankAccountLinkage(verifiedBank);
            }
            if (hasText(verifiedBank.getCybrillaBankVerificationId()) && poaBankVerificationMissingInvestorIdentifier(verifiedBank)) {
                clearFpBankAccountLinkage(verifiedBank);
            }
            investorBankAccountRepository.save(verifiedBank);
        }
        return ensureMfInvestmentAccount(investorId);
    }

    private boolean fpBankAccountMatchesLocal(InvestorBankAccount bankAccount) {
        if (!hasText(bankAccount.getCybrillaBankId()) || !hasText(bankAccount.getAccountNumber())) {
            return false;
        }
        try {
            JsonNode fpBank = cybrillaClient.fetchBankAccount(bankAccount.getCybrillaBankId());
            String fpAccountNumber = fpBank.path("account_number").asText("").trim();
            if (!bankAccount.getAccountNumber().trim().equals(fpAccountNumber)) {
                return false;
            }
            if (!hasText(bankAccount.getIfscCode())) {
                return true;
            }
            String fpIfsc = fpBank.path("ifsc_code").asText("").trim().toUpperCase(Locale.ROOT);
            return !hasText(fpIfsc) || bankAccount.getIfscCode().trim().equalsIgnoreCase(fpIfsc);
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "fp_bank_match status='failed' bank_account_id='{}' external_bank_id='{}' reason='{}'",
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankId(),
                    ex.getMessage());
            return false;
        }
    }

    private boolean poaBankVerificationPanMismatch(Investor investor, InvestorBankAccount bankAccount) {
        if (!hasText(investor.getPan()) || !hasText(bankAccount.getCybrillaBankVerificationId())) {
            return false;
        }
        try {
            JsonNode verification = cybrillaClient.fetchBankAccountVerification(bankAccount.getCybrillaBankVerificationId());
            String poaPan = verification.path("pan").path("value").asText("");
            if (!hasText(poaPan)) {
                poaPan = verification.path("investor_identifier").asText("");
            }
            return hasText(poaPan) && !investor.getPan().trim().equalsIgnoreCase(poaPan.trim());
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "poa_bav_pan_check status='failed' bank_account_id='{}' external_verification_id='{}' reason='{}'",
                    bankAccount.getId(),
                    bankAccount.getCybrillaBankVerificationId(),
                    ex.getMessage());
            return false;
        }
    }

    private boolean poaBankVerificationMissingInvestorIdentifier(InvestorBankAccount bankAccount) {
        if (!hasText(bankAccount.getExternalVerificationResponseJson())) {
            return false;
        }
        try {
            JsonNode verification = OBJECT_MAPPER.readTree(bankAccount.getExternalVerificationResponseJson());
            JsonNode identifier = verification.get("investor_identifier");
            return identifier == null || identifier.isNull() || !StringUtils.hasText(identifier.asText());
        } catch (Exception ex) {
            return false;
        }
    }

    private static void clearPoaBankVerificationOnly(InvestorBankAccount bankAccount) {
        bankAccount.setCybrillaBankVerificationId(null);
        bankAccount.setCybrillaBankVerificationStatus(null);
        bankAccount.setCybrillaBankVerificationConfidence(null);
        bankAccount.setExternalVerificationResponseJson(null);
    }

    private static void clearFpBankAccountLinkage(InvestorBankAccount bankAccount) {
        bankAccount.setCybrillaBankId(null);
        bankAccount.setFpBankAccountOldId(null);
        bankAccount.setCybrillaBankVerificationId(null);
        bankAccount.setCybrillaBankVerificationStatus(null);
        bankAccount.setCybrillaBankVerificationConfidence(null);
        bankAccount.setExternalVerificationResponseJson(null);
    }

    private boolean mfInvestmentAccountMatchesProfile(Investor investor) {
        if (!hasResolvableCybrillaInvestorId(investor) || !hasResolvableMfInvestmentAccountId(investor)) {
            return false;
        }
        try {
            JsonNode accounts = cybrillaClient.listMfInvestmentAccounts(investor.getCybrillaInvestorId());
            JsonNode data = accounts == null ? null : accounts.path("data");
            if (data == null || !data.isArray()) {
                return false;
            }
            String expectedAccountId = investor.getExternalMfInvestmentAccountId().trim();
            String expectedProfileId = investor.getCybrillaInvestorId().trim();
            for (JsonNode account : data) {
                if (!expectedAccountId.equalsIgnoreCase(account.path("id").asText(""))) {
                    continue;
                }
                return expectedProfileId.equalsIgnoreCase(account.path("primary_investor").asText(""));
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "mf_investment_account_profile_check status='failed' investor_id='{}' reason='{}'",
                    investor.getId(),
                    ex.getMessage()
            );
        }
        return false;
    }

    private String truncateExternalSyncMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        String trimmed = message.trim();
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 997) + "...";
    }

    private void markExternalProfileSyncDeferred(UUID investorId, String message) {
        if (deferredPersistTx == null) {
            return;
        }
        try {
            deferredPersistTx.executeWithoutResult(status ->
                    investorRepository.findById(investorId).ifPresent(fresh -> {
                        fresh.setExternalSyncPending(Boolean.TRUE);
                        fresh.setExternalSyncMessage(truncateExternalSyncMessage(message));
                        investorRepository.save(fresh);
                    })
            );
        } catch (RuntimeException persistEx) {
            logger.warn(
                    "external_profile_defer_persist_failed investor_id='{}' reason='{}'",
                    investorId,
                    persistEx.getMessage()
            );
        }
    }

    @Transactional
    public Investor updateInvestor(UUID investorId, InvestorUpdateRequest request) {
        return updateInvestor(investorId, request, null);
    }

    @Transactional
    public Investor updateInvestor(UUID investorId, InvestorUpdateRequest request, UUID actorId) {
        Investor investor = getInvestor(investorId);
        if (actorId != null) {
            assertCanManageInvestor(actorId, investor, "Cannot update another distributor's investor");
        }
        boolean identityChanged = false;
        if (request.pan() != null) {
            String newPan = normalizePan(request.pan());
            if (newPan != null && !newPan.equals(investor.getPan())) {
                PanFormat.validateForDatabase(newPan);
                if (investor.getKycStatus() == KycStatus.COMPLETED) {
                    throw new IllegalArgumentException("PAN cannot be changed after KYC is completed");
                }
                investorRepository.findByPan(newPan).ifPresent(existing -> {
                    if (!existing.getId().equals(investorId)) {
                        throw new DuplicateResourceException("An investor with this PAN already exists");
                    }
                });
                investor.setPan(newPan);
                identityChanged = true;
            }
        }
        if (request.fullName() != null && !request.fullName().equals(investor.getFullName())) {
            investor.setFullName(request.fullName());
            identityChanged = true;
        }
        if (request.mobileNumber() != null) investor.setMobileNumber(request.mobileNumber());
        if (request.email() != null) investor.setEmail(request.email());
        if (request.dateOfBirth() != null && !request.dateOfBirth().equals(investor.getDateOfBirth())) {
            investor.setDateOfBirth(request.dateOfBirth());
            identityChanged = true;
        }
        if (request.anniversaryDate() != null) investor.setAnniversaryDate(request.anniversaryDate());
        if (request.goalMaturityDate() != null) investor.setGoalMaturityDate(request.goalMaturityDate());
        if (request.addressLine1() != null) investor.setAddressLine1(request.addressLine1());
        if (request.addressLine2() != null) investor.setAddressLine2(request.addressLine2());
        if (request.city() != null) investor.setCity(request.city());
        if (request.state() != null) investor.setState(request.state());
        if (request.postalCode() != null) investor.setPostalCode(request.postalCode());
        if (request.relationshipType() != null || request.householdId() != null || request.householdName() != null
                || request.guardianInvestorId() != null || request.guardianPan() != null) {
            applyFamilyStructure(
                    investor,
                    request.relationshipType(),
                    request.householdId(),
                    request.householdName(),
                    request.guardianInvestorId(),
                    request.guardianPan(),
                    investor.getDistributorId()
            );
        }
        if (request.onboardingNotes() != null) investor.setOnboardingNotes(request.onboardingNotes());
        if (identityChanged) {
            InvestorKycStateReset.resetForIdentityChange(investor);
        }
        Investor saved = investorRepository.save(investor);
        try {
            if (saved.getCybrillaInvestorId() == null || saved.getCybrillaInvestorId().isBlank()) {
                String externalInvestorId = cybrillaClient.createInvestorProfile(saved);
                saved.setCybrillaInvestorId(externalInvestorId);
                saved = investorRepository.save(saved);
            } else {
                cybrillaClient.updateInvestorProfile(saved);
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "Investor {} saved locally but FP profile update is pending: {}",
                    saved.getId(),
                    ex.getMessage()
            );
            saved.setExternalSyncPending(true);
            saved.setExternalSyncMessage("Investor details were saved locally, but the Fintech Primitives profile could not be updated right now.");
        }
        return saved;
    }

    public List<Investor> findByPostalCode(String postalCode) {
        return investorRepository.findByPostalCode(postalCode);
    }

    /**
     * Scoped variant of {@link #findByPostalCode(String)} for the controller layer.
     * ADMIN role returns all matches (rare operator tooling); distributors see only their own
     * investors, plus their downline's if they are a MASTER_DISTRIBUTOR. Soft-deleted rows are
     * excluded by the {@code @SQLRestriction("is_deleted = false")} on the Investor entity.
     * Closes the BUG-002 IDOR: the previous endpoint took no Authentication.
     */
    public List<Investor> findByPostalCode(String postalCode, UUID requesterId, DistributorRole role) {
        List<Investor> all = investorRepository.findByPostalCode(postalCode);
        if (role == DistributorRole.ADMIN) {
            return all;
        }
        Distributor requester = distributorService.getDistributor(requesterId);
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            UUID masterId = requester.getMasterDistributorId() != null
                    ? requester.getMasterDistributorId()
                    : requesterId;
            return all.stream()
                    .filter(i -> masterId.equals(distributorMasterIdOf(i.getDistributorId())))
                    .toList();
        }
        return all.stream()
                .filter(i -> requesterId.equals(i.getDistributorId()))
                .toList();
    }

    private UUID distributorMasterIdOf(UUID distributorId) {
        try {
            Distributor d = distributorService.getDistributor(distributorId);
            return d.getMasterDistributorId() != null ? d.getMasterDistributorId() : distributorId;
        } catch (EntityNotFoundException ex) {
            return distributorId;
        }
    }

    private String normalizeSearchQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query must contain at least 1 character");
        }
        return query.trim();
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 10;
        }
        return Math.min(limit, 50);
    }

    private KycStatus parseKycStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        String normalizedStatus = status.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
        if ("KYC_VERIFIED".equals(normalizedStatus) || "VERIFIED".equals(normalizedStatus)) {
            return KycStatus.COMPLETED;
        }
        return KycStatus.valueOf(normalizedStatus);
    }

    @Transactional
    public java.util.Map<String, Object> deleteInvestor(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        assertCanManageInvestor(actorId, investor, "Cannot delete another distributor's investor");
        investor.setIsDeleted(true);
        investor.setDeletedAt(LocalDateTime.now());
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "DELETED", actorId, "{\"softDeleted\":true}");
        String cybrillaId = investor.getCybrillaInvestorId();
        return java.util.Map.of(
                "status", "archived",
                "investorId", investorId.toString(),
                "cybrillaInvestorId", cybrillaId == null ? "" : cybrillaId,
                "message", StringUtils.hasText(cybrillaId)
                        ? "Investor archived in Platizio only. Finprim profile "
                        + cybrillaId
                        + " remains (Finprim has no public DELETE API). Use Restore from Cybrilla to bring them back."
                        : "Investor archived in Platizio. No Finprim profile id was linked on this row."
        );
    }

    private boolean canResumeOnboardingDraft(Investor existing, UUID distributorId) {
        if (existing.getDistributorId() == null || !existing.getDistributorId().equals(distributorId)) {
            return false;
        }
        if (existing.getKycStatus() == KycStatus.COMPLETED) {
            return false;
        }
        InvestorStatus status = existing.getInvestorStatus();
        return status == InvestorStatus.DRAFT || status == InvestorStatus.ONBOARDING;
    }

    private void assertEmailAvailableForInvestor(String email, UUID investorId) {
        if (email == null) {
            return;
        }
        investorRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(investorId)) {
                // BUG-001: include the existing investor id so a client can resume it.
                throw new DuplicateResourceException(
                        "An investor with this email address already exists", existing.getId(), "email");
            }
        });
    }

    private Investor resumeOnboardingDraft(Investor existing, InvestorCreateRequest request, UUID actorId) {
        InvestorUpdateRequest update = new InvestorUpdateRequest(
                cleanText(request.fullName()),
                cleanText(request.mobileNumber()),
                cleanText(request.email()),
                normalizePan(request.pan()),
                request.dateOfBirth(),
                request.anniversaryDate(),
                request.goalMaturityDate(),
                cleanText(request.addressLine1()),
                cleanText(request.addressLine2()),
                cleanText(request.city()),
                cleanText(request.state()),
                cleanText(request.postalCode()),
                request.householdId(),
                request.householdName(),
                request.relationshipType(),
                request.guardianInvestorId(),
                request.guardianPan(),
                cleanText(request.onboardingNotes())
        );
        Investor saved = updateInvestor(existing.getId(), update, actorId);
        if (saved.getInvestorStatus() == InvestorStatus.DRAFT) {
            saved.setInvestorStatus(InvestorStatus.ONBOARDING);
            saved = investorRepository.save(saved);
        }
        auditService.log(
                "INVESTOR",
                saved.getId(),
                "ONBOARDING_RESUMED",
                request.distributorId(),
                "{\"pan_provided\":true}"
        );
        return saved;
    }

    private void assertCanManageInvestor(UUID actorId, Investor investor, String deniedMessage) {
        assertCanManageInvestorForDistributor(actorId, investor.getDistributorId(), deniedMessage);
    }

    private void assertCanManageInvestorForDistributor(UUID actorId, UUID distributorId, String deniedMessage) {
        Distributor actor = distributorService.getDistributor(actorId);
        if (actor.getRole() == DistributorRole.ADMIN || actorId.equals(distributorId)) {
            return;
        }
        if (actor.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            Distributor owner = distributorService.getDistributor(distributorId);
            if (actorId.equals(owner.getMasterDistributorId())) {
                return;
            }
        }
        throw new AccessDeniedException(deniedMessage);
    }

    private void applyFamilyStructure(
            Investor investor,
            InvestorRelationshipType requestedRelationshipType,
            UUID requestedHouseholdId,
            String requestedHouseholdName,
            UUID requestedGuardianInvestorId,
            String requestedGuardianPan,
            UUID distributorId
    ) {
        InvestorRelationshipType relationshipType = requestedRelationshipType == null
                ? (investor.getRelationshipType() == null ? InvestorRelationshipType.SELF : investor.getRelationshipType())
                : requestedRelationshipType;

        UUID householdId = requestedHouseholdId != null ? requestedHouseholdId : investor.getHouseholdId();
        UUID guardianInvestorId = requestedGuardianInvestorId != null ? requestedGuardianInvestorId : investor.getGuardianInvestorId();
        String guardianPan = normalizePan(requestedGuardianPan != null ? requestedGuardianPan : investor.getGuardianPan());

        if (guardianInvestorId != null) {
            Investor guardian = getInvestor(guardianInvestorId);
            if (!distributorId.equals(guardian.getDistributorId())) {
                throw new AccessDeniedException("Guardian investor must belong to the same distributor");
            }
            householdId = guardian.getHouseholdId() != null ? guardian.getHouseholdId() : guardian.getId();
            if (guardianPan == null || guardianPan.isBlank()) {
                guardianPan = normalizePan(guardian.getPan());
            }
        }

        if (requestedHouseholdId != null
                && !investorRepository.existsByHouseholdIdAndDistributorId(householdId, distributorId)) {
            throw new EntityNotFoundException("Household not found for distributor");
        }

        if (relationshipType == InvestorRelationshipType.MINOR && guardianInvestorId == null && (guardianPan == null || guardianPan.isBlank())) {
            throw new IllegalArgumentException("Minor folios require guardian PAN or guardian investor link");
        }

        if (relationshipType != InvestorRelationshipType.MINOR) {
            guardianInvestorId = null;
            guardianPan = null;
        }
        if (householdId == null) {
            householdId = investor.getId() == null ? UUID.randomUUID() : investor.getId();
        }

        investor.setRelationshipType(relationshipType);
        investor.setHouseholdId(householdId);
        investor.setHouseholdName(blankToNull(requestedHouseholdName != null ? requestedHouseholdName : investor.getHouseholdName()));
        investor.setGuardianInvestorId(guardianInvestorId);
        investor.setGuardianPan(guardianPan);
    }

    private String normalizePan(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return PanFormat.normalize(cleanText(value));
    }

    private boolean hasResolvableCybrillaInvestorId(Investor investor) {
        return investor != null && ExternalReferenceIds.isFpInvestorProfileId(investor.getCybrillaInvestorId());
    }

    private boolean hasResolvableMfInvestmentAccountId(Investor investor) {
        return investor != null && ExternalReferenceIds.isFpMfInvestmentAccountId(investor.getExternalMfInvestmentAccountId());
    }

    private void clearPlaceholderExternalReferences(Investor investor) {
        if (investor == null) {
            return;
        }
        if (hasText(investor.getCybrillaInvestorId()) && !ExternalReferenceIds.isFpInvestorProfileId(investor.getCybrillaInvestorId())) {
            investor.setCybrillaInvestorId(null);
        }
        if (hasText(investor.getExternalMfInvestmentAccountId())
                && !ExternalReferenceIds.isFpMfInvestmentAccountId(investor.getExternalMfInvestmentAccountId())) {
            investor.setExternalMfInvestmentAccountId(null);
        }
    }

    private String blankToNull(String value) {
        String cleaned = cleanText(value);
        if (cleaned == null || cleaned.isBlank()) {
            return null;
        }
        return cleaned.trim();
    }

    private String investorExternalSyncMessage(CybrillaApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("given pan is not valid")) {
            return "Investor created, but KYC is incomplete. Unable to post investor data to Cybrilla/Fintech Primitives because PAN verification failed.";
        }
        if (message.contains("not a valid name")) {
            return "Investor created, but KYC is incomplete. Unable to post investor data to Cybrilla/Fintech Primitives because the investor name was rejected.";
        }
        return "Investor created, but KYC is incomplete. Unable to post investor data to Cybrilla/Fintech Primitives right now.";
    }

    private String completedKycExternalProfileMessage(CybrillaApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("given pan is not valid")) {
            return "KYC is complete locally, but the investor profile could not be created in Cybrilla/Fintech Primitives because PAN verification failed.";
        }
        if (message.contains("not a valid name")) {
            return "KYC is complete locally, but the investor profile could not be created in Cybrilla/Fintech Primitives because the investor name was rejected.";
        }
        String detail = ex.getMessage() == null ? "" : ex.getMessage().trim();
        if (StringUtils.hasText(detail) && detail.length() <= 240) {
            return "KYC is complete locally, but the investor profile could not be created in Cybrilla/Fintech Primitives: " + detail;
        }
        return "KYC is complete locally, but the investor profile could not be created in Cybrilla/Fintech Primitives right now.";
    }

    private String bankExternalSyncMessage(CybrillaApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("investor must have")) {
            return "Unable to post bank data to Cybrilla/Fintech Primitives because the investor profile is still pending. Bank details were saved locally for retry.";
        }
        return "Unable to post bank data to Cybrilla/Fintech Primitives right now. Bank details were saved locally for retry.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String textOrDefault(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private void applyBankVerificationResponse(Investor investor, InvestorBankAccount bankAccount, JsonNode verification) {
        if ("pre_verification".equalsIgnoreCase(textOrNull(verification, "object"))) {
            applyPoaBankPreVerificationResponse(investor, bankAccount, verification);
            return;
        }

        String status = textOrNull(verification, "status");
        String confidence = textOrNull(verification, "confidence");
        bankAccount.setCybrillaBankVerificationStatus(status);
        bankAccount.setCybrillaBankVerificationConfidence(confidence);
        bankAccount.setExternalSyncPending(false);
        bankAccount.setExternalSyncMessage(null);

        if ("completed".equalsIgnoreCase(status) && isVerifiedConfidence(confidence)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
            if (investor.getKycStatus() == KycStatus.COMPLETED) {
                investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
            }
            return;
        }

        if ("failed".equalsIgnoreCase(status) || ("completed".equalsIgnoreCase(status) && !isVerifiedConfidence(confidence))) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
            return;
        }

        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
    }

    private void applyPoaBankPreVerificationResponse(Investor investor, InvestorBankAccount bankAccount, JsonNode verification) {
        String preVerificationStatus = textOrNull(verification, "status");
        JsonNode bankVerification = firstBankAccountResult(verification);
        String bankStatus = textOrNull(bankVerification, "status");
        String bankCode = textOrNull(bankVerification, "code");
        bankAccount.setCybrillaBankVerificationStatus(bankStatus != null ? bankStatus : preVerificationStatus);
        bankAccount.setCybrillaBankVerificationConfidence(bankCode);
        bankAccount.setExternalSyncPending(false);
        bankAccount.setExternalSyncMessage(null);

        if ("verified".equalsIgnoreCase(bankStatus)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
            if (investor.getKycStatus() == KycStatus.COMPLETED) {
                investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
            }
            return;
        }

        if ("failed".equalsIgnoreCase(bankStatus) && isBankManualFollowUpCode(bankCode)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            bankAccount.setExternalSyncMessage("POA bank pre-verification requires manual follow-up: " + bankCode);
            return;
        }

        if ("failed".equalsIgnoreCase(bankStatus) && isBankRetryableCode(bankCode)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            bankAccount.setExternalSyncMessage("POA bank pre-verification can be retried: " + bankCode);
            return;
        }

        if ("failed".equalsIgnoreCase(bankStatus)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
            return;
        }

        if (!"completed".equalsIgnoreCase(preVerificationStatus) || bankStatus == null) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            return;
        }

        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
    }

    private boolean isBankManualFollowUpCode(String bankCode) {
        return "uncertain".equalsIgnoreCase(bankCode)
                || "bank_account_proof_required".equalsIgnoreCase(bankCode);
    }

    private boolean isBankRetryableCode(String bankCode) {
        return "low_confidence".equalsIgnoreCase(bankCode)
                || "bank_verification_failed".equalsIgnoreCase(bankCode)
                || "upstream_error".equalsIgnoreCase(bankCode);
    }

    private JsonNode firstBankAccountResult(JsonNode verification) {
        if (verification == null || !verification.has("bank_accounts") || !verification.path("bank_accounts").isArray()
                || verification.path("bank_accounts").isEmpty()) {
            return null;
        }
        return verification.path("bank_accounts").get(0);
    }

    private boolean isVerifiedConfidence(String confidence) {
        return "very_high".equalsIgnoreCase(confidence) || "high".equalsIgnoreCase(confidence);
    }

    private void enrichBankDetailsFromIfsc(InvestorBankAccount bankAccount) {
        if (!hasText(bankAccount.getIfscCode())) {
            return;
        }
        if (hasText(bankAccount.getBankName()) && hasText(bankAccount.getBranchName())) {
            return;
        }
        try {
            IfscLookupResult lookup = cybrillaClient.fetchIfscDetails(bankAccount.getIfscCode());
            if (!hasText(bankAccount.getBankName()) && hasText(lookup.bankName())) {
                bankAccount.setBankName(lookup.bankName());
            }
            if (!hasText(bankAccount.getBranchName()) && hasText(lookup.branchName())) {
                bankAccount.setBranchName(lookup.branchName());
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "ifsc_lookup status='failed' ifsc='{}' reason='{}'",
                    bankAccount.getIfscCode(),
                    ex.getMessage()
            );
        }
    }

    private String normalizeIfscCode(String ifscCode) {
        return ifscCode == null ? null : ifscCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeBankAccountType(String accountType) {
        if (!hasText(accountType)) {
            return "savings";
        }
        String normalized = accountType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "savings", "current", "nre_savings", "nro_savings" -> normalized;
            default -> throw new IllegalArgumentException(
                    "Unsupported bank account type '" + accountType + "'. Supported values: savings, current, nre_savings, nro_savings"
            );
        };
    }

    private JsonNode webhookDataObject(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode dataObject = payload.path("data").path("object");
        return dataObject.isMissingNode() || dataObject.isNull() ? payload : dataObject;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode() || node.path(fieldName).isNull()) {
            return null;
        }
        return node.path(fieldName).asText();
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\u0000", "");
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
}

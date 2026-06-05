package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorOnboardingResumeResponse;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestorService implements BankVerificationStarter {

    private static final Logger logger = LoggerFactory.getLogger(InvestorService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<BankVerificationStatus> AUTO_BANK_SYNC_STATUSES = List.of(
            BankVerificationStatus.VERIFICATION_PENDING,
            BankVerificationStatus.CAPTURED
    );

    private final InvestorRepository investorRepository;
    private final InvestorBankAccountRepository investorBankAccountRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;
    private final CybrillaClient cybrillaClient;
    private final long scheduledBankSyncFailureBackoffMs;
    private volatile long scheduledBankSyncBackoffUntilEpochMillis;

    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient
    ) {
        this(investorRepository, investorBankAccountRepository, distributorService, auditService, cybrillaClient, 900_000);
    }

    @Autowired
    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            @Value("${app.bank-sync.failure-backoff-ms:900000}") long scheduledBankSyncFailureBackoffMs
    ) {
        this.investorRepository = investorRepository;
        this.investorBankAccountRepository = investorBankAccountRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
        this.cybrillaClient = cybrillaClient;
        this.scheduledBankSyncFailureBackoffMs = Math.max(0, scheduledBankSyncFailureBackoffMs);
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
        String email = cleanText(request.email());

        investorRepository.findByPan(pan).ifPresent(existing -> {
            throw new DuplicateResourceException("An investor with this PAN already exists");
        });
        // B-18: investors.email had no UNIQUE constraint; check here so the caller
        // receives a clear 409 rather than a raw DataIntegrityViolationException.
        if (email != null) {
            investorRepository.findByEmail(email).ifPresent(existing -> {
                throw new DuplicateResourceException("An investor with this email address already exists");
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

    @Transactional
    public InvestorBankAccount addBankAccount(UUID investorId, InvestorBankRequest request, UUID actorId) {
        Investor investor = getInvestor(investorId);
        listVisibleToDistributor(actorId, investor.getDistributorId());

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        bankAccount.setInvestorId(investorId);
        bankAccount.setAccountHolderName(cleanText(request.accountHolderName()));
        bankAccount.setAccountNumber(cleanText(request.accountNumber()));
        bankAccount.setIfscCode(cleanText(request.ifscCode()));
        bankAccount.setBankName(cleanText(request.bankName()));
        bankAccount.setBranchName(cleanText(request.branchName()));
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
        if (!hasText(investor.getCybrillaInvestorId())) {
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
        if (!hasText(saved.getCybrillaInvestorId())) {
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

        if (!hasText(saved.getExternalMfInvestmentAccountId())) {
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
        if (!hasText(investor.getCybrillaInvestorId())) {
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
            Map<String, Object> requestSnapshot = new LinkedHashMap<>();
            verification = cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(
                    bankAccount.getCybrillaBankVerificationId(),
                    requestSnapshot
            );
            bankAccount.setExternalVerificationRequestJson(writeJson(requestSnapshot));
            bankAccount.setExternalVerificationResponseJson(writeJson(verification));
            applyBankVerificationResponse(investor, bankAccount, verification);
        } catch (CybrillaApiException ex) {
            return markBankVerificationPending(investor, bankAccount, bankExternalSyncMessage(ex), actorId);
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

    @Transactional(readOnly = true)
    public InvestorOnboardingResumeResponse getOnboardingResume(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId, actorId);
        List<InvestorBankAccount> bankAccounts = investorBankAccountRepository.findByInvestorId(investorId);
        boolean hasExternalKycReference = hasText(investor.getExternalKycCheckId()) || hasText(investor.getExternalKycRequestId());
        boolean hasBankAccount = !bankAccounts.isEmpty();
        boolean readyForTransactions = investor.getKycStatus() == KycStatus.COMPLETED
                && investor.getBankVerificationStatus() == BankVerificationStatus.VERIFIED;
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
                readyForTransactions,
                message
        );
    }

    @Transactional
    public Investor ensureMfInvestmentAccount(UUID investorId) {
        Investor investor = getInvestor(investorId);
        if (investor.getExternalMfInvestmentAccountId() != null && !investor.getExternalMfInvestmentAccountId().isBlank()) {
            return investor;
        }
        if (investor.getCybrillaInvestorId() == null || investor.getCybrillaInvestorId().isBlank()) {
            throw new IllegalStateException("Fintech Primitives investor profile is required before opening an MF investment account");
        }
        String externalMfInvestmentAccountId = cybrillaClient.createMfInvestmentAccount(investor);
        investor.setExternalMfInvestmentAccountId(externalMfInvestmentAccountId);
        return investorRepository.save(investor);
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
        if (request.fullName() != null) investor.setFullName(request.fullName());
        if (request.mobileNumber() != null) investor.setMobileNumber(request.mobileNumber());
        if (request.email() != null) investor.setEmail(request.email());
        if (request.dateOfBirth() != null) investor.setDateOfBirth(request.dateOfBirth());
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
    public void deleteInvestor(UUID investorId, UUID actorId) {
        Investor investor = getInvestor(investorId);
        assertCanManageInvestor(actorId, investor, "Cannot delete another distributor's investor");
        investor.setIsDeleted(true);
        investor.setDeletedAt(LocalDateTime.now());
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "DELETED", actorId, "{\"softDeleted\":true}");
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
        return cleanText(value).trim().toUpperCase(Locale.ROOT);
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

        if (!"completed".equalsIgnoreCase(preVerificationStatus) || bankStatus == null) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            return;
        }

        if ("verified".equalsIgnoreCase(bankStatus)) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
            if (investor.getKycStatus() == KycStatus.COMPLETED) {
                investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
            }
            return;
        }

        if ("failed".equalsIgnoreCase(bankStatus)
                && ("uncertain".equalsIgnoreCase(bankCode) || "bank_account_proof_required".equalsIgnoreCase(bankCode))) {
            bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
            bankAccount.setExternalSyncMessage("POA bank pre-verification requires manual follow-up: " + bankCode);
            return;
        }

        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_FAILED);
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

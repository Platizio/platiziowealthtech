package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestorService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorService.class);

    private final InvestorRepository investorRepository;
    private final InvestorBankAccountRepository investorBankAccountRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;
    private final CybrillaClient cybrillaClient;

    public InvestorService(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository investorBankAccountRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient
    ) {
        this.investorRepository = investorRepository;
        this.investorBankAccountRepository = investorBankAccountRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
        this.cybrillaClient = cybrillaClient;
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
        if (requester.getRole() == DistributorRole.ADMIN || requester.getId().equals(distributorId)) {
            return investorRepository.findByDistributorId(distributorId);
        }
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            Distributor targetDistributor = distributorService.getDistributor(distributorId);
            if (requester.getId().equals(targetDistributor.getMasterDistributorId())) {
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

        try {
            cybrillaClient.captureBankAccount(investor, savedBank);
        } catch (CybrillaApiException ex) {
            String externalMessage = bankExternalSyncMessage(ex);
            logger.warn(
                    "Bank account {} saved locally with verification pending because external bank sync failed: {}",
                    savedBank.getId(),
                    ex.getMessage()
            );
            savedBank.setExternalSyncPending(true);
            savedBank.setExternalSyncMessage(externalMessage);
            auditService.log(
                    "INVESTOR_BANK",
                    savedBank.getId(),
                    "EXTERNAL_BANK_SYNC_PENDING",
                    actorId,
                    "{\"investorId\":\"" + investorId + "\",\"externalBankSyncPending\":true}"
            );
            return savedBank;
        }
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
        if (bankAccount.getCybrillaBankVerificationId() == null || bankAccount.getCybrillaBankVerificationId().isBlank()) {
            throw new IllegalStateException("Bank verification has not been started for this account");
        }

        JsonNode verification = cybrillaClient.fetchBankAccountVerification(bankAccount.getCybrillaBankVerificationId());
        applyBankVerificationResponse(investor, bankAccount, verification);
        InvestorBankAccount savedBank = investorBankAccountRepository.save(bankAccount);
        investorRepository.save(investor);
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
        Investor investor = getInvestor(investorId);
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
        return investorRepository.save(investor);
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
        investor.setIsDeleted(true);
        investor.setDeletedAt(LocalDateTime.now());
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "DELETED", actorId, "{\"softDeleted\":true}");
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

    private String bankExternalSyncMessage(CybrillaApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("investor must have")) {
            return "Unable to post bank data to Cybrilla/Fintech Primitives because the investor profile is still pending. Bank details were saved locally for retry.";
        }
        return "Unable to post bank data to Cybrilla/Fintech Primitives right now. Bank details were saved locally for retry.";
    }

    private void applyBankVerificationResponse(Investor investor, InvestorBankAccount bankAccount, JsonNode verification) {
        String status = textOrNull(verification, "status");
        String confidence = textOrNull(verification, "confidence");
        bankAccount.setCybrillaBankVerificationStatus(status);
        bankAccount.setCybrillaBankVerificationConfidence(confidence);

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
}

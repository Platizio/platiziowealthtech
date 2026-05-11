package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestorService {

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

    public List<Investor> listAll() {
        return investorRepository.findAll();
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
        investorRepository.findByPan(request.pan()).ifPresent(existing -> {
            throw new IllegalArgumentException("Investor with same PAN already exists");
        });
        distributorService.getDistributor(request.distributorId());

        Investor investor = new Investor();
        investor.setDistributorId(request.distributorId());
        investor.setFullName(request.fullName());
        investor.setMobileNumber(request.mobileNumber());
        investor.setEmail(request.email());
        investor.setPan(request.pan());
        investor.setDateOfBirth(request.dateOfBirth());
        investor.setAddressLine1(request.addressLine1());
        investor.setAddressLine2(request.addressLine2());
        investor.setCity(request.city());
        investor.setState(request.state());
        investor.setPostalCode(request.postalCode());
        investor.setOnboardingNotes(request.onboardingNotes());
        investor.setInvestorStatus(InvestorStatus.ONBOARDING);

        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "CREATED", request.distributorId(), "{\"pan\":\"" + request.pan() + "\"}");

        String externalInvestorId = cybrillaClient.createInvestorProfile(saved);
        saved.setCybrillaInvestorId(externalInvestorId);
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

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        bankAccount.setInvestorId(investorId);
        bankAccount.setAccountHolderName(request.accountHolderName());
        bankAccount.setAccountNumber(request.accountNumber());
        bankAccount.setIfscCode(request.ifscCode());
        bankAccount.setBankName(request.bankName());
        bankAccount.setBranchName(request.branchName());
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);

        InvestorBankAccount savedBank = investorBankAccountRepository.save(bankAccount);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investorRepository.save(investor);

        cybrillaClient.captureBankAccount(investor, savedBank);
        auditService.log("INVESTOR_BANK", savedBank.getId(), "BANK_ADDED", actorId, "{\"investorId\":\"" + investorId + "\"}");
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
    public Investor updateInvestor(UUID investorId, InvestorUpdateRequest request) {
        Investor investor = getInvestor(investorId);
        if (request.fullName() != null) investor.setFullName(request.fullName());
        if (request.mobileNumber() != null) investor.setMobileNumber(request.mobileNumber());
        if (request.email() != null) investor.setEmail(request.email());
        if (request.dateOfBirth() != null) investor.setDateOfBirth(request.dateOfBirth());
        if (request.addressLine1() != null) investor.setAddressLine1(request.addressLine1());
        if (request.addressLine2() != null) investor.setAddressLine2(request.addressLine2());
        if (request.city() != null) investor.setCity(request.city());
        if (request.state() != null) investor.setState(request.state());
        if (request.postalCode() != null) investor.setPostalCode(request.postalCode());
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
        investorRepository.delete(investor);
        auditService.log("INVESTOR", investorId, "DELETED", actorId, "{}");
    }
}

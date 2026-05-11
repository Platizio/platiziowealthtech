package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.dto.DistributorSignupRequest;
import com.platizio.wealthtech.dto.DistributorUpdateRequest;
import com.platizio.wealthtech.repository.DistributorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DistributorService {

    private final DistributorRepository distributorRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public DistributorService(DistributorRepository distributorRepository, AuditService auditService, PasswordEncoder passwordEncoder) {
        this.distributorRepository = distributorRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Distributor> findAll() {
        return distributorRepository.findAll();
    }

    public List<Distributor> search(String query, UUID requesterId, int limit) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query must contain at least 1 character");
        }
        int normalizedLimit = limit < 1 ? 10 : Math.min(limit, 50);
        if (requesterId == null) {
            return distributorRepository.search(query.trim(), PageRequest.of(0, normalizedLimit));
        }

        Distributor requester = getDistributor(requesterId);
        if (requester.getRole() == DistributorRole.ADMIN) {
            return distributorRepository.search(query.trim(), PageRequest.of(0, normalizedLimit));
        }
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            return distributorRepository.searchByMasterDistributor(
                    requester.getId(),
                    query.trim(),
                    PageRequest.of(0, normalizedLimit)
            );
        }
        throw new IllegalStateException("Only an admin or master distributor can search distributors");
    }

    public Distributor login(AuthLoginRequest request) {
        Distributor distributor = distributorRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // First check the plaintext 'password' field I added for you
        if (request.password().equals(distributor.getPassword())) {
            return distributor;
        }

        // Then check the secure BCrypt 'password_hash'
        if (distributor.getPasswordHash() != null && 
            passwordEncoder.matches(request.password(), distributor.getPasswordHash())) {
            return distributor;
        }

        throw new IllegalArgumentException("Invalid email or password");
    }

    public List<Distributor> findSubDistributors(UUID requesterId) {
        Distributor requester = getDistributor(requesterId);
        if (requester.getRole() == DistributorRole.ADMIN) {
            return distributorRepository.findByRole(DistributorRole.SUB_DISTRIBUTOR);
        }
        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            return distributorRepository.findByMasterDistributorId(requester.getId());
        }
        throw new IllegalStateException("Only an admin or master distributor can fetch sub distributors");
    }

    @Transactional
    public Distributor signup(DistributorSignupRequest request) {
        if (distributorRepository.existsByArnNumber(request.arnNumber())) {
            throw new IllegalArgumentException("Distributor with same ARN already exists");
        }
        if (request.eUinNumber() != null && distributorRepository.existsByEUinNumber(request.eUinNumber())) {
            throw new IllegalArgumentException("Distributor with same EUIN already exists");
        }

        DistributorRole role = request.role() == null ? DistributorRole.MASTER_DISTRIBUTOR : request.role();
        validateDistributorHierarchy(role, request.masterDistributorId());

        Distributor distributor = new Distributor();
        distributor.setFullName(request.fullName());
        distributor.setMobileNumber(request.mobileNumber());
        distributor.setEmail(request.email());
        distributor.setArnNumber(request.arnNumber());
        distributor.setNismCertificateNumber(request.nismCertificateNumber());
        distributor.setNismExpiryDate(request.nismExpiryDate());
        distributor.setArnExpiryDate(request.arnExpiryDate());
        distributor.seteUinNumber(request.eUinNumber());
        distributor.setBankAccountNumber(request.bankAccountNumber());
        distributor.setBankIfsc(request.bankIfsc());
        distributor.setBankAccountHolderName(request.bankAccountHolderName());
        distributor.setRole(role);
        distributor.setMasterDistributorId(request.masterDistributorId());
        distributor.setInternalRm(Boolean.TRUE.equals(request.internalRm()));
        distributor.setPasswordHash(passwordEncoder.encode(request.password()));
        distributor.setStatus(DistributorStatus.PENDING_APPROVAL);
        distributor.setProfileCompletionPercent(85);

        Distributor saved = distributorRepository.save(distributor);
        auditService.log("DISTRIBUTOR", saved.getId(), "SIGNUP_SUBMITTED", saved.getId(), "{\"status\":\"PENDING_APPROVAL\"}");
        return saved;
    }

    public Distributor getDistributor(UUID distributorId) {
        return distributorRepository.findById(distributorId)
                .orElseThrow(() -> new EntityNotFoundException("Distributor not found"));
    }

    @Transactional
    public Distributor update(UUID distributorId, DistributorUpdateRequest request) {
        Distributor distributor = getDistributor(distributorId);
        if (request.fullName() != null) distributor.setFullName(request.fullName());
        if (request.mobileNumber() != null) distributor.setMobileNumber(request.mobileNumber());
        if (request.email() != null) distributor.setEmail(request.email());
        if (request.nismCertificateNumber() != null) distributor.setNismCertificateNumber(request.nismCertificateNumber());
        if (request.nismExpiryDate() != null) distributor.setNismExpiryDate(request.nismExpiryDate());
        if (request.arnExpiryDate() != null) distributor.setArnExpiryDate(request.arnExpiryDate());
        if (request.eUinNumber() != null) distributor.seteUinNumber(request.eUinNumber());
        if (request.bankAccountNumber() != null) distributor.setBankAccountNumber(request.bankAccountNumber());
        if (request.bankIfsc() != null) distributor.setBankIfsc(request.bankIfsc());
        if (request.bankAccountHolderName() != null) distributor.setBankAccountHolderName(request.bankAccountHolderName());
        return distributorRepository.save(distributor);
    }

    @Transactional
    public Distributor updateStatus(UUID distributorId, DistributorStatus status, UUID actorId) {
        Distributor distributor = getDistributor(distributorId);
        distributor.setStatus(status);
        Distributor saved = distributorRepository.save(distributor);
        auditService.log("DISTRIBUTOR", saved.getId(), "STATUS_UPDATED", actorId, "{\"status\":\"" + status + "\"}");
        return saved;
    }

    private void validateDistributorHierarchy(DistributorRole role, UUID masterDistributorId) {
        if (role == DistributorRole.SUB_DISTRIBUTOR) {
            if (masterDistributorId == null) {
                throw new IllegalArgumentException("Sub distributor must have a masterDistributorId");
            }
            Distributor master = getDistributor(masterDistributorId);
            if (master.getRole() != DistributorRole.MASTER_DISTRIBUTOR) {
                throw new IllegalArgumentException("masterDistributorId must point to a master distributor");
            }
            return;
        }

        if (masterDistributorId != null) {
            throw new IllegalArgumentException("Only sub distributors can have a masterDistributorId");
        }
    }

    @Transactional
    public void deleteDistributor(UUID distributorId, UUID actorId) {
        Distributor distributor = getDistributor(distributorId);
        distributorRepository.delete(distributor);
        auditService.log("DISTRIBUTOR", distributorId, "DELETED", actorId, "{\"reason\":\"User requested deletion\"}");
    }
}

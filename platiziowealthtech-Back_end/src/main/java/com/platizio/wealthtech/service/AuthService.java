package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.AccountNotApprovedException;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.repository.DistributorRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final DistributorRepository distributorRepository;
    private final DistributorService distributorService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthService(
            DistributorRepository distributorRepository,
            DistributorService distributorService,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuditService auditService
    ) {
        this.distributorRepository = distributorRepository;
        this.distributorService = distributorService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public AuthResponse signup(AuthSignupRequest request) {
        if (distributorRepository.existsByArnNumber(request.arnNumber())) {
            throw new IllegalArgumentException("Distributor with same ARN already exists");
        }
        if (distributorRepository.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        DistributorRole role = request.role() == null ? DistributorRole.SUB_DISTRIBUTOR : request.role();

        Distributor distributor = new Distributor();
        distributor.setFullName(request.fullName());
        distributor.setMobileNumber(request.mobileNumber());
        distributor.setEmail(request.email());
        distributor.setArnNumber(request.arnNumber());
        distributor.setNismCertificateNumber(request.nismCertificateNumber());
        distributor.setNismExpiryDate(request.nismExpiryDate());
        distributor.seteUinNumber(request.eUinNumber());
        distributor.setBankAccountNumber(request.bankAccountNumber());
        distributor.setBankIfsc(request.bankIfsc());
        distributor.setBankAccountHolderName(request.bankAccountHolderName());
        distributor.setRole(role);
        distributor.setMasterDistributorId(request.masterDistributorId());
        distributor.setInternalRm(Boolean.TRUE.equals(request.internalRm()));
        distributor.setStatus(DistributorStatus.PENDING_APPROVAL);
        distributor.setProfileCompletionPercent(85);
        distributor.setPasswordHash(passwordEncoder.encode(request.password()));

        Distributor saved = distributorRepository.save(distributor);
        auditService.log("DISTRIBUTOR", saved.getId(), "SIGNUP_SUBMITTED", saved.getId(), "{\"status\":\"PENDING_APPROVAL\"}");

        return new AuthResponse(
                null,
                saved.getId(),
                saved.getEmail(),
                saved.getFullName(),
                saved.getRole(),
                saved.getStatus(),
                "Approval remaining. Your account is pending admin approval."
        );
    }

    public AuthResponse login(AuthLoginRequest request) {
        Distributor distributor = distributorRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (distributor.getPasswordHash() == null ||
                !passwordEncoder.matches(request.password(), distributor.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (distributor.getStatus() != DistributorStatus.APPROVED) {
            throw new AccountNotApprovedException(distributor.getStatus());
        }

        String token = jwtService.generateToken(
                distributor.getId(), distributor.getEmail(), distributor.getRole().name());
        return new AuthResponse(
                token, distributor.getId(), distributor.getEmail(),
                distributor.getFullName(), distributor.getRole(), distributor.getStatus(), "Login successful");
    }

    public AuthResponse currentUser(String email) {
        Distributor distributor = distributorRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
        return new AuthResponse(
                null,
                distributor.getId(),
                distributor.getEmail(),
                distributor.getFullName(),
                distributor.getRole(),
                distributor.getStatus(),
                "Authenticated"
        );
    }
}

package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.LinkRequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorLinkRequestRepository extends JpaRepository<InvestorLinkRequest, UUID> {

    /** Resolve a link by the SHA-256 of its opaque token (the email-link lookup). */
    Optional<InvestorLinkRequest> findFirstByTokenHash(String tokenHash);

    /** The current live (PENDING) request for an investor, if any. */
    Optional<InvestorLinkRequest> findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
            UUID investorId, LinkRequestStatus status);

    /** All link requests for an investor, newest first. */
    List<InvestorLinkRequest> findByInvestorIdOrderByCreatedAtDesc(UUID investorId);
}

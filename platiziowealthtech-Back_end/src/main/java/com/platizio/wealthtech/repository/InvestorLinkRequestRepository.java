package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkRequestStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorLinkRequestRepository extends JpaRepository<InvestorLinkRequest, UUID> {

    /** Resolve a request by its opaque email-link token. */
    Optional<InvestorLinkRequest> findByToken(String token);

    /** The most recent request for the investor in a given status (e.g. the live PENDING one). */
    Optional<InvestorLinkRequest> findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
            UUID investorId, InvestorLinkRequestStatus status);
}

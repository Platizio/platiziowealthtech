package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.domain.ProfileChangeApprovalStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileChangeApprovalChallengeRepository
        extends JpaRepository<ProfileChangeApprovalChallenge, UUID> {

    /**
     * The most recent challenge for an investor whose status is one of the supplied
     * set. Used to find the current live challenge (PENDING | CHALLENGE_SENT |
     * APPROVED) to supersede.
     */
    Optional<ProfileChangeApprovalChallenge> findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(
            UUID investorId, Collection<ProfileChangeApprovalStatus> statuses);

    /** The most recent challenge for an investor in exactly the given status. */
    Optional<ProfileChangeApprovalChallenge> findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
            UUID investorId, ProfileChangeApprovalStatus status);

    /** All challenges for an investor (any status), most recent first. */
    List<ProfileChangeApprovalChallenge> findByInvestorId(UUID investorId);
}

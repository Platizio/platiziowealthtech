package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProfileChangeChallenge;
import com.platizio.wealthtech.domain.ProfileChangeStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileChangeChallengeRepository extends JpaRepository<ProfileChangeChallenge, UUID> {

    /**
     * The most recent challenge for an investor whose status is one of the supplied
     * set — used to find the current live challenge (PENDING | CHALLENGE_SENT |
     * APPROVED) or the CONSUMED gate record.
     */
    Optional<ProfileChangeChallenge> findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(
            UUID investorId, Collection<ProfileChangeStatus> statuses);

    /** The most recent challenge for an investor in exactly the given status. */
    Optional<ProfileChangeChallenge> findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
            UUID investorId, ProfileChangeStatus status);

    /** All challenges for an investor account in any of the supplied statuses. */
    List<ProfileChangeChallenge> findByInvestorAccountIdAndStatusIn(
            UUID investorAccountId, Collection<ProfileChangeStatus> statuses);
}

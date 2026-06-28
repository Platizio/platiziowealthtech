package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionApprovalStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionApprovalChallengeRepository
        extends JpaRepository<TransactionApprovalChallenge, UUID> {

    /**
     * The most recent challenge for a transaction whose status is one of the
     * supplied set. Used to find the current live challenge (PENDING |
     * CHALLENGE_SENT | APPROVED) or the CONSUMED gate record.
     */
    Optional<TransactionApprovalChallenge> findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(
            UUID transactionId, Collection<TransactionApprovalStatus> statuses);

    /** The most recent challenge for a transaction in exactly the given status. */
    Optional<TransactionApprovalChallenge> findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
            UUID transactionId, TransactionApprovalStatus status);

    /** All challenges for an investor account in any of the supplied statuses. */
    List<TransactionApprovalChallenge> findByInvestorAccountIdAndStatusIn(
            UUID investorAccountId, Collection<TransactionApprovalStatus> statuses);
}

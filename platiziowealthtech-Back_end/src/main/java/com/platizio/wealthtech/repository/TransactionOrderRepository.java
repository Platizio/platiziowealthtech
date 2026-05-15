package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionOrderRepository extends JpaRepository<TransactionOrder, UUID> {
    List<TransactionOrder> findByInvestorId(UUID investorId);
    List<TransactionOrder> findByDistributorId(UUID distributorId);

    /**
     * Fetch all orders for a distributor of a given type whose createdAt
     * falls after the supplied cutoff. Used to build the SIP trend chart
     * over a rolling 6-month window without pulling the full order history.
     */
    List<TransactionOrder> findByDistributorIdAndTransactionTypeAndCreatedAtAfter(
            UUID distributorId,
            TransactionType transactionType,
            OffsetDateTime createdAtAfter);
}
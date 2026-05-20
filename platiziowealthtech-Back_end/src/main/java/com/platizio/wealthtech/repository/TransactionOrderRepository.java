package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.TransactionType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionOrderRepository extends JpaRepository<TransactionOrder, UUID>, JpaSpecificationExecutor<TransactionOrder> {
    interface OrderStatusCount {
        OrderStatus getOrderStatus();
        long getTotal();
    }

    List<TransactionOrder> findByInvestorId(UUID investorId);
    List<TransactionOrder> findByDistributorId(UUID distributorId);
    Page<TransactionOrder> findByDistributorIdAndTransactionType(
            UUID distributorId,
            TransactionType transactionType,
            Pageable pageable);

    @Query("""
            select o.orderStatus as orderStatus, count(o) as total
            from TransactionOrder o
            where o.distributorId = :distributorId
              and o.transactionType = :transactionType
            group by o.orderStatus
            """)
    List<OrderStatusCount> countByDistributorIdAndTransactionTypeGroupedByOrderStatus(
            @Param("distributorId") UUID distributorId,
            @Param("transactionType") TransactionType transactionType);

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

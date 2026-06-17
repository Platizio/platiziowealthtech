package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.TransactionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    interface InvestorOrderAmountSummary {
        UUID getInvestorId();
        BigDecimal getTotalAmount();
        BigDecimal getCompletedAmount();
        long getOrderCount();
    }

    List<TransactionOrder> findByInvestorId(UUID investorId);
    List<TransactionOrder> findByDistributorId(UUID distributorId);
    /**
     * Looks up a local order by the FP/Cybrilla external id stored on it (the
     * {@code mfp_} mf_purchase id). Used by the webhook handler to reconcile
     * {@code mf_purchase.*}/{@code payment.*} events idempotently against
     * authoritative provider state.
     */
    Optional<TransactionOrder> findByExternalOrderId(String externalOrderId);
    /**
     * Looks up a SIP order by the FP integer mandate id stored on it. Used by the webhook handler to
     * reconcile {@code mandate.*} events (BUG-047) against authoritative provider mandate state.
     */
    Optional<TransactionOrder> findFirstByExternalMandateId(Integer externalMandateId);
    /**
     * Unbounded variant used by the SIP dashboard service (B-71). Realistic
     * per-distributor SIP volume is in the low hundreds, well within memory
     * limits, and consistent with the unpaginated
     * findByDistributorIdAndTransactionTypeAndCreatedAtAfter query below.
     */
    List<TransactionOrder> findByDistributorIdAndTransactionType(
            UUID distributorId,
            TransactionType transactionType);

    /** Paginated variant retained for any consumer that legitimately needs paging. */
    Optional<TransactionOrder> findByInvestorActionToken(String investorActionToken);
    List<TransactionOrder> findByDistributorIdAndOrderStatus(
            UUID distributorId,
            OrderStatus orderStatus,
            Pageable pageable);
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

    List<TransactionOrder> findByDistributorIdAndOrderStatusInAndCreatedAtBefore(
            UUID distributorId,
            Collection<OrderStatus> orderStatuses,
            OffsetDateTime createdAtBefore);

    /**
     * Used by DemoOrderAdvancer (@Profile("local") only) to find orders that
     * have been sitting in a "waiting for provider" state long enough that the
     * demo scheduler should advance them. updatedAt is bumped by BaseEntity's
     * @PreUpdate hook on every save, so once OrderService.updateOrderStatus
     * advances an order, this query no longer matches it for another cycle.
     */
    List<TransactionOrder> findByOrderStatusInAndUpdatedAtBefore(
            Collection<OrderStatus> orderStatuses,
            OffsetDateTime updatedAtBefore);

    @Query(value = """
            select
                investor_id as "investorId",
                coalesce(sum(amount), 0) as "totalAmount",
                coalesce(sum(case when order_status in ('SUCCESSFUL', 'COMPLETED') then amount else 0 end), 0) as "completedAmount",
                count(*) as "orderCount"
            from transaction_orders
            where is_deleted = false
              and distributor_id = :distributorId
            group by investor_id
            """, nativeQuery = true)
    List<InvestorOrderAmountSummary> summarizeAmountsByDistributor(@Param("distributorId") UUID distributorId);
}

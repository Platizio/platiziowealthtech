package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionOrderRepository extends JpaRepository<TransactionOrder, UUID> {
    List<TransactionOrder> findByInvestorId(UUID investorId);
    List<TransactionOrder> findByDistributorId(UUID distributorId);
}
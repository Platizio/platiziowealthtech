package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.RedemptionRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RedemptionRecordRepository extends JpaRepository<RedemptionRecord, UUID> {
    List<RedemptionRecord> findByInvestorId(UUID investorId);
    List<RedemptionRecord> findByOrderId(UUID orderId);
}
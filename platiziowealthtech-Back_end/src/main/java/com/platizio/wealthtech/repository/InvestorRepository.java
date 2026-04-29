package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Investor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorRepository extends JpaRepository<Investor, UUID> {
    Optional<Investor> findByPan(String pan);
    List<Investor> findByDistributorId(UUID distributorId);
    List<Investor> findByDistributorIdIn(List<UUID> distributorIds);
    List<Investor> findByPostalCode(String postalCode);
}

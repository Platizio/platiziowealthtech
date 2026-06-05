package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ExternalApiSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalApiSnapshotRepository extends JpaRepository<ExternalApiSnapshot, UUID> {
}

package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.LeadInteraction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadInteractionRepository extends JpaRepository<LeadInteraction, UUID> {
    List<LeadInteraction> findByLeadIdOrderByCreatedAtDesc(UUID leadId);
}
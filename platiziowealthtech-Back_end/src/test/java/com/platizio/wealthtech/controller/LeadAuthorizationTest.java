package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.LeadInteraction;
import com.platizio.wealthtech.domain.LeadSource;
import com.platizio.wealthtech.domain.LeadStatus;
import com.platizio.wealthtech.dto.LeadAssignRequest;
import com.platizio.wealthtech.dto.LeadCreateRequest;
import com.platizio.wealthtech.dto.LeadCreateWithDistributorRequest;
import com.platizio.wealthtech.dto.LeadInteractionRequest;
import com.platizio.wealthtech.dto.LeadStatusUpdateRequest;
import com.platizio.wealthtech.dto.UpdateLeadRequest;
import com.platizio.wealthtech.repository.InvestorLeadRepository;
import com.platizio.wealthtech.repository.LeadInteractionRepository;
import com.platizio.wealthtech.service.LeadService;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

class LeadAuthorizationTest {

    private static final String LEAD_MUTATION_AUTH =
            "hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')";
    private static final String DISTRIBUTOR_EDIT_AUTH = "hasRole('DISTRIBUTOR')";

    @Test
    void leadManagementEndpointsRequireDistributorRoles() throws NoSuchMethodException {
        assertPreAuthorize(LeadController.class.getMethod("getById", UUID.class));
        assertPreAuthorize(LeadController.class.getMethod("createLead", LeadCreateRequest.class));
        assertPreAuthorize(LeadController.class.getMethod(
                "createLeadWithDistributor", LeadCreateWithDistributorRequest.class, Authentication.class));
        assertPreAuthorize(LeadController.class.getMethod("listByDistributor", UUID.class));
        assertThat(LeadController.class.getMethod(
                        "updateLead", UUID.class, UpdateLeadRequest.class, Authentication.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo(DISTRIBUTOR_EDIT_AUTH);
        assertPreAuthorize(LeadController.class.getMethod(
                "assignLead", UUID.class, LeadAssignRequest.class, Authentication.class));
        assertPreAuthorize(LeadController.class.getMethod(
                "updateStatus", UUID.class, LeadStatusUpdateRequest.class, Authentication.class));
        assertPreAuthorize(LeadController.class.getMethod(
                "addInteraction", UUID.class, LeadInteractionRequest.class, Authentication.class));
    }

    @Test
    void statusUpdateRejectsLeadAssignedToAnotherDistributor() {
        UUID assignedDistributorId = UUID.randomUUID();
        UUID callerDistributorId = UUID.randomUUID();
        LeadService leadService = new LeadService(
                leadRepository(lead(assignedDistributorId)),
                interactionRepository(new AtomicReference<>()),
                null
        );

        assertThatThrownBy(() -> leadService.updateStatus(
                UUID.randomUUID(),
                new LeadStatusUpdateRequest(LeadStatus.CONTACTED, "Called"),
                callerDistributorId
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void interactionRejectsLeadAssignedToAnotherDistributor() {
        UUID assignedDistributorId = UUID.randomUUID();
        UUID callerDistributorId = UUID.randomUUID();
        AtomicReference<LeadInteraction> savedInteraction = new AtomicReference<>();
        LeadService leadService = new LeadService(
                leadRepository(lead(assignedDistributorId)),
                interactionRepository(savedInteraction),
                null
        );

        assertThatThrownBy(() -> leadService.addInteraction(
                UUID.randomUUID(),
                new LeadInteractionRequest("Called prospect", "CALL"),
                callerDistributorId
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(savedInteraction.get()).isNull();
    }

    @Test
    void interactionUsesAuthenticatedDistributorId() {
        UUID callerDistributorId = UUID.randomUUID();
        AtomicReference<LeadInteraction> savedInteraction = new AtomicReference<>();
        LeadService leadService = new LeadService(
                leadRepository(lead(callerDistributorId)),
                interactionRepository(savedInteraction),
                null
        );

        leadService.addInteraction(
                UUID.randomUUID(),
                new LeadInteractionRequest("Called prospect", "CALL"),
                callerDistributorId
        );

        assertThat(savedInteraction.get().getDistributorId()).isEqualTo(callerDistributorId);
    }

    private void assertPreAuthorize(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(LEAD_MUTATION_AUTH);
    }

    private InvestorLead lead(UUID assignedDistributorId) {
        InvestorLead lead = new InvestorLead();
        lead.setProspectName("Prospect");
        lead.setMobileNumber("9999999999");
        lead.setSource(LeadSource.MANUAL);
        lead.setStatus(LeadStatus.ASSIGNED);
        lead.setAssignedDistributorId(assignedDistributorId);
        return lead;
    }

    private InvestorLeadRepository leadRepository(InvestorLead lead) {
        return (InvestorLeadRepository) Proxy.newProxyInstance(
                InvestorLeadRepository.class.getClassLoader(),
                new Class<?>[]{InvestorLeadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(lead);
                    case "save" -> args[0];
                    case "findAll", "findByAssignedDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private LeadInteractionRepository interactionRepository(AtomicReference<LeadInteraction> savedInteraction) {
        return (LeadInteractionRepository) Proxy.newProxyInstance(
                LeadInteractionRepository.class.getClassLoader(),
                new Class<?>[]{LeadInteractionRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        savedInteraction.set((LeadInteraction) args[0]);
                        return args[0];
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}

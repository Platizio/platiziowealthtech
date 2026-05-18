package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.LeadSource;
import com.platizio.wealthtech.domain.LeadStatus;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.LeadAssignRequest;
import com.platizio.wealthtech.dto.LeadCreateWithDistributorRequest;
import com.platizio.wealthtech.dto.LeadStatusUpdateRequest;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.DistributorService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.LeadService;
import com.platizio.wealthtech.service.OrderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AuditActorControllerTest {

    @Test
    void investorAuditEndpointsUseAuthenticatedActor() {
        RecordingInvestorService investorService = new RecordingInvestorService();
        InvestorController controller = new InvestorController(investorService);
        UUID actorId = UUID.randomUUID();
        Authentication auth = auth(actorId);

        controller.updateKycStatus(UUID.randomUUID(), KycStatus.COMPLETED, auth);
        controller.addBank(UUID.randomUUID(), new InvestorBankRequest("Name", "123", "IFSC0001", null, null), auth);
        controller.verifyBank(UUID.randomUUID(), auth);
        controller.deleteInvestor(UUID.randomUUID(), auth);

        assertThat(investorService.actorIds).containsExactly(actorId, actorId, actorId, actorId);
    }

    @Test
    void distributorAuditEndpointsUseAuthenticatedActor() {
        RecordingDistributorService distributorService = new RecordingDistributorService();
        DistributorController controller = new DistributorController(distributorService);
        UUID actorId = UUID.randomUUID();
        Authentication auth = auth(actorId);

        controller.updateStatus(UUID.randomUUID(), DistributorStatus.APPROVED, auth);
        controller.deleteDistributor(UUID.randomUUID(), auth);

        assertThat(distributorService.actorIds).containsExactly(actorId, actorId);
    }

    @Test
    void orderAuditEndpointsUseAuthenticatedActor() {
        RecordingOrderService orderService = new RecordingOrderService();
        OrderController controller = new OrderController(orderService);
        UUID actorId = UUID.randomUUID();
        Authentication auth = auth(actorId);

        controller.updateStatus(UUID.randomUUID(), OrderStatus.COMPLETED, null, auth);
        controller.createRedemption(UUID.randomUUID(), auth);
        controller.deleteOrder(UUID.randomUUID(), auth);

        assertThat(orderService.actorIds).containsExactly(actorId, actorId, actorId);
    }

    @Test
    void leadAuditEndpointsUseAuthenticatedActor() {
        RecordingLeadService leadService = new RecordingLeadService();
        LeadController controller = new LeadController(leadService);
        UUID actorId = UUID.randomUUID();
        Authentication auth = auth(actorId);

        controller.createLeadWithDistributor(new LeadCreateWithDistributorRequest(
                "Prospect",
                "9999999999",
                null,
                null,
                null,
                LeadSource.MANUAL,
                null,
                UUID.randomUUID()
        ), auth);
        controller.assignLead(UUID.randomUUID(), new LeadAssignRequest(UUID.randomUUID()), auth);
        controller.updateStatus(UUID.randomUUID(), new LeadStatusUpdateRequest(LeadStatus.CONTACTED, "Called"), auth);
        controller.deleteLead(UUID.randomUUID(), auth);

        assertThat(leadService.actorIds).containsExactly(actorId, actorId, actorId, actorId);
    }

    private Authentication auth(UUID distributorId) {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.ADMIN,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static class RecordingInvestorService extends InvestorService {

        private final java.util.ArrayList<UUID> actorIds = new java.util.ArrayList<>();

        RecordingInvestorService() {
            super(null, null, null, null, null);
        }

        @Override
        public Investor updateKycStatus(UUID investorId, KycStatus kycStatus, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public InvestorBankAccount addBankAccount(UUID investorId, InvestorBankRequest request, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public Investor verifyBank(UUID investorId, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public void deleteInvestor(UUID investorId, UUID actorId) {
            actorIds.add(actorId);
        }
    }

    private static class RecordingDistributorService extends DistributorService {

        private final java.util.ArrayList<UUID> actorIds = new java.util.ArrayList<>();

        RecordingDistributorService() {
            super(null, null, null);
        }

        @Override
        public Distributor updateStatus(UUID distributorId, DistributorStatus status, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public void deleteDistributor(UUID distributorId, UUID actorId) {
            actorIds.add(actorId);
        }
    }

    private static class RecordingOrderService extends OrderService {

        private final java.util.ArrayList<UUID> actorIds = new java.util.ArrayList<>();

        RecordingOrderService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public TransactionOrder updateOrderStatus(UUID orderId, OrderStatus status, String failureReason, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public RedemptionRecord createRedemption(UUID orderId, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public void deleteOrder(UUID orderId, UUID actorId) {
            actorIds.add(actorId);
        }
    }

    private static class RecordingLeadService extends LeadService {

        private final java.util.ArrayList<UUID> actorIds = new java.util.ArrayList<>();

        RecordingLeadService() {
            super(null, null, null);
        }

        @Override
        public InvestorLead createLeadWithDistributor(LeadCreateWithDistributorRequest request, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public InvestorLead assignLead(UUID leadId, LeadAssignRequest request, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public InvestorLead updateStatus(UUID leadId, LeadStatusUpdateRequest request, UUID actorId) {
            actorIds.add(actorId);
            return null;
        }

        @Override
        public void deleteLead(UUID leadId, UUID actorId) {
            actorIds.add(actorId);
        }
    }
}

package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkRequestStatus;
import com.platizio.wealthtech.dto.SendToInvestorRequest;
import com.platizio.wealthtech.dto.SendToInvestorResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.InvestorLinkRequestService;
import com.platizio.wealthtech.service.InvestorService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class InvestorControllerSendToInvestorTest {

    @Mock private InvestorService investorService;
    @Mock private InvestorLinkRequestService investorLinkRequestService;
    private InvestorController controller;

    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InvestorController(
                investorService, null, null, null, null, null, investorLinkRequestService);
    }

    private SendToInvestorRequest request() {
        return new SendToInvestorRequest(
                "Asha Rao", "9999999999", "asha@example.com", "ABCDE1234F",
                LocalDate.of(1990, 1, 1), "{\"step\":1}");
    }

    @Test
    void sendToInvestorParksPendingAndReturnsTokenOn200() throws Exception {
        Investor parked = new Investor();
        setInvestorId(parked, investorId);
        when(investorService.createOrUpdatePendingInvestor(any(SendToInvestorRequest.class), eq(distributorId)))
                .thenReturn(parked);

        InvestorLinkRequest linkRequest = new InvestorLinkRequest();
        linkRequest.setInvestorId(investorId);
        linkRequest.setToken("rawtoken123");
        linkRequest.setStatus(InvestorLinkRequestStatus.PENDING);
        linkRequest.setExpiresAt(OffsetDateTime.now().plusDays(7));
        when(investorLinkRequestService.sendToInvestor(eq(investorId), eq(distributorId), eq("{\"step\":1}")))
                .thenReturn(linkRequest);

        SendToInvestorResponse response = controller.sendToInvestor(request(), auth(distributorId));

        assertThat(response.status()).isEqualTo("PENDING_INVESTOR_APPROVAL");
        assertThat(response.message()).isEqualTo("Investor approval is pending.");
        assertThat(response.investorId()).isEqualTo(investorId);
        assertThat(response.approvalToken()).isEqualTo("rawtoken123");
        verify(investorLinkRequestService).sendToInvestor(investorId, distributorId, "{\"step\":1}");
    }

    @Test
    void sendToInvestorRejectsAlreadyLinkedPan() {
        when(investorService.createOrUpdatePendingInvestor(any(SendToInvestorRequest.class), eq(distributorId)))
                .thenThrow(new IllegalStateException(
                        "An investor with this PAN is already linked to a distributor."));

        // IllegalStateException → 400 BAD_REQUEST via GlobalExceptionHandler.
        assertThatThrownBy(() -> controller.sendToInvestor(request(), auth(distributorId)))
                .isInstanceOf(IllegalStateException.class);
        verify(investorLinkRequestService, never()).sendToInvestor(any(), any(), any());
    }

    private static void setInvestorId(Investor investor, UUID id) throws Exception {
        java.lang.reflect.Field idField =
                com.platizio.wealthtech.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(investor, id);
    }

    private Authentication auth(UUID distributorId) {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}

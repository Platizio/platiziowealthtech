package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionApprovalStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionApprovalChallengeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransactionApprovalServiceTest {

    @Mock private TransactionApprovalChallengeRepository challengeRepository;
    @Mock private TransactionOrderRepository orderRepository;
    @Mock private RedemptionRecordRepository redemptionRepository;
    @Mock private InvestorAccountRepository accountRepository;
    @Mock private ConsentRecordService consentRecordService;
    @Mock private OtpService otpService;
    @Mock private AuditService auditService;

    private TransactionApprovalService service;

    private final UUID transactionId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID investorAccountId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final String email = "investor@example.com";

    @BeforeEach
    void setUp() {
        service = new TransactionApprovalService(
                challengeRepository, orderRepository, redemptionRepository,
                accountRepository, consentRecordService, otpService, auditService);
        lenient().when(challengeRepository.save(any(TransactionApprovalChallenge.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountRepository.findById(investorAccountId))
                .thenReturn(Optional.of(account()));
        lenient().when(otpService.requestOtp(anyString(), eq(OtpPurpose.TRANSACTION_APPROVAL), any()))
                .thenReturn(new OtpRequestResponse("sent", 300, 30, null));
        lenient().when(consentRecordService.record(
                anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(consentRecord());
    }

    private InvestorAccount account() {
        InvestorAccount a = new InvestorAccount();
        ReflectionTestUtils.setField(a, "id", investorAccountId);
        a.setEmail(email);
        a.setInvestorId(investorId);
        a.setFullName("Test Investor");
        return a;
    }

    private ConsentRecord consentRecord() {
        ConsentRecord c = new ConsentRecord();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private TransactionOrder purchaseOrder() {
        TransactionOrder o = new TransactionOrder();
        ReflectionTestUtils.setField(o, "id", transactionId);
        o.setInvestorId(investorId);
        o.setDistributorId(distributorId);
        o.setProductSchemeId(UUID.randomUUID());
        o.setTransactionType(TransactionType.PURCHASE);
        o.setAmount(new BigDecimal("5000.00"));
        o.setPaymentMode("NETBANKING");
        return o;
    }

    private RedemptionRecord redemption() {
        RedemptionRecord r = new RedemptionRecord();
        ReflectionTestUtils.setField(r, "id", transactionId);
        r.setOrderId(UUID.randomUUID());
        r.setInvestorId(investorId);
        r.setUnits(new BigDecimal("12.5"));
        r.setAmount(new BigDecimal("1500.00"));
        return r;
    }

    private TransactionApprovalChallenge challenge(TransactionApprovalStatus status) {
        TransactionApprovalChallenge c = new TransactionApprovalChallenge();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setTransactionId(transactionId);
        c.setTransactionType(TransactionType.PURCHASE);
        c.setInvestorId(investorId);
        c.setInvestorAccountId(investorAccountId);
        c.setStatus(status);
        c.setSnapshotJson("{\"k\":1}");
        c.setSnapshotSha256(ConsentRecordService.sha256("{\"k\":1}"));
        c.setConsentTemplateVersion("v1.0");
        c.setConsentRenderedText("consent");
        c.setChannel("EMAIL");
        c.setOtpPurpose(OtpPurpose.TRANSACTION_APPROVAL);
        c.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        c.setDeliveryAttempts(0);
        return c;
    }

    // ---- createChallenge ----------------------------------------------------

    @Test
    void createChallengeFreezesSnapshotAndHashAtPending() {
        when(orderRepository.findById(transactionId)).thenReturn(Optional.of(purchaseOrder()));
        when(challengeRepository.findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(eq(transactionId), any()))
                .thenReturn(Optional.empty());

        TransactionApprovalChallenge created = service.createChallenge(
                transactionId, TransactionType.PURCHASE, investorAccountId);

        assertThat(created.getStatus()).isEqualTo(TransactionApprovalStatus.PENDING);
        assertThat(created.getSnapshotJson()).isNotBlank();
        assertThat(created.getSnapshotSha256())
                .isEqualTo(ConsentRecordService.sha256(created.getSnapshotJson()));
        assertThat(created.getInvestorId()).isEqualTo(investorId);
        assertThat(created.getInvestorAccountId()).isEqualTo(investorAccountId);
        assertThat(created.getConsentRenderedText()).isNotBlank();
    }

    @Test
    void createChallengeSupersedesAnExistingLiveChallenge() {
        TransactionApprovalChallenge live = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        when(orderRepository.findById(transactionId)).thenReturn(Optional.of(purchaseOrder()));
        when(challengeRepository.findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(eq(transactionId), any()))
                .thenReturn(Optional.of(live));

        TransactionApprovalChallenge created = service.createChallenge(
                transactionId, TransactionType.PURCHASE, investorAccountId);

        assertThat(live.getStatus()).isEqualTo(TransactionApprovalStatus.SUPERSEDED);
        assertThat(created.getStatus()).isEqualTo(TransactionApprovalStatus.PENDING);
    }

    @Test
    void createRedemptionChallengeSnapshotsRedemptionFields() {
        when(redemptionRepository.findById(transactionId)).thenReturn(Optional.of(redemption()));
        when(challengeRepository.findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(eq(transactionId), any()))
                .thenReturn(Optional.empty());

        TransactionApprovalChallenge created = service.createChallenge(
                transactionId, TransactionType.REDEMPTION, investorAccountId);

        assertThat(created.getTransactionType()).isEqualTo(TransactionType.REDEMPTION);
        assertThat(created.getSnapshotJson()).contains("units").contains("12.5");
    }

    @Test
    void createChallengeRejectsWhenOrderMissing() {
        when(orderRepository.findById(transactionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createChallenge(
                transactionId, TransactionType.PURCHASE, investorAccountId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void createChallengeRejectsWhenAccountDoesNotOwnTransaction() {
        TransactionOrder foreign = purchaseOrder();
        foreign.setInvestorId(UUID.randomUUID()); // different investor
        when(orderRepository.findById(transactionId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.createChallenge(
                transactionId, TransactionType.PURCHASE, investorAccountId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- requestApprovalOtp -------------------------------------------------

    @Test
    void requestApprovalOtpFlipsToChallengeSentAndIncrementsAttempts() {
        TransactionApprovalChallenge pending = challenge(TransactionApprovalStatus.PENDING);
        UUID challengeId = pending.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(pending));

        OtpRequestResponse response = service.requestApprovalOtp(challengeId, investorAccountId, false);

        assertThat(pending.getStatus()).isEqualTo(TransactionApprovalStatus.CHALLENGE_SENT);
        assertThat(pending.getDeliveryAttempts()).isEqualTo(1);
        assertThat(pending.getExpiresAt()).isNotNull();
        assertThat(response.devCode()).isNull();
        verify(otpService).requestOtp(eq(email), eq(OtpPurpose.TRANSACTION_APPROVAL), eq(challengeId));
    }

    @Test
    void requestApprovalOtpResendIncrementsAttemptsAgainAndNeverLeaksCode() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        sent.setDeliveryAttempts(1);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));

        OtpRequestResponse response = service.requestApprovalOtp(challengeId, distributorId, true);

        assertThat(sent.getDeliveryAttempts()).isEqualTo(2);
        assertThat(sent.getStatus()).isEqualTo(TransactionApprovalStatus.CHALLENGE_SENT);
        assertThat(response.devCode()).isNull();
        verify(otpService).requestOtp(eq(email), eq(OtpPurpose.TRANSACTION_APPROVAL), eq(challengeId));
    }

    // ---- approve ------------------------------------------------------------

    @Test
    void approveHappyPathRecordsConsentAndMovesToApproved() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));

        TransactionApprovalChallenge approved = service.approve(
                challengeId, investorAccountId, "123456", true,
                "203.0.113.7", "Mozilla/5.0", "sess-1");

        assertThat(approved.getStatus()).isEqualTo(TransactionApprovalStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getConsentRecordId()).isNotNull();
        assertThat(approved.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(approved.getSessionId()).isEqualTo("sess-1");
        verify(otpService).verify(eq(email), eq(OtpPurpose.TRANSACTION_APPROVAL), eq("123456"), eq(challengeId));
        verify(consentRecordService).record(
                eq(ConsentRecordService.SUBJECT_INVESTOR), eq(investorAccountId),
                eq("purchase_approval"), anyString(), anyString(), any(), any());
    }

    @Test
    void approveRejectsBadOrExpiredOtp() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));
        org.mockito.Mockito.doThrow(new BadCredentialsException("bad"))
                .when(otpService).verify(eq(email), eq(OtpPurpose.TRANSACTION_APPROVAL), eq("000000"), eq(challengeId));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "000000", true, null, null, null))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(sent.getStatus()).isEqualTo(TransactionApprovalStatus.CHALLENGE_SENT);
    }

    @Test
    void approveRejectsWhenChallengeExpired() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        sent.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", true, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
    }

    @Test
    void approveRejectsConsentNotAcceptedWithoutCallingVerify() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
        assertThat(sent.getStatus()).isEqualTo(TransactionApprovalStatus.CHALLENGE_SENT);
    }

    @Test
    void approveRejectsWhenAccountDoesNotOwnTransaction() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        UUID otherAccountId = UUID.randomUUID();
        InvestorAccount other = new InvestorAccount();
        ReflectionTestUtils.setField(other, "id", otherAccountId);
        other.setEmail("other@example.com");
        other.setInvestorId(UUID.randomUUID()); // different investor than the txn
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(sent));
        when(accountRepository.findById(otherAccountId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.approve(
                challengeId, otherAccountId, "123456", true, null, null, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
    }

    @Test
    void approveRejectsWhenStatusNotChallengeSent() {
        TransactionApprovalChallenge pending = challenge(TransactionApprovalStatus.PENDING);
        UUID challengeId = pending.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", true, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- supersedeOnEdit ----------------------------------------------------

    @Test
    void supersedeOnEditMovesLiveChallengeToSuperseded() {
        TransactionApprovalChallenge live = challenge(TransactionApprovalStatus.APPROVED);
        when(challengeRepository.findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(eq(transactionId), any()))
                .thenReturn(Optional.of(live));

        service.supersedeOnEdit(transactionId, distributorId);

        assertThat(live.getStatus()).isEqualTo(TransactionApprovalStatus.SUPERSEDED);
        verify(auditService).log(anyString(), eq(transactionId), anyString(), eq(distributorId), any());
    }

    @Test
    void supersedeOnEditIsNoOpWhenNoLiveChallenge() {
        when(challengeRepository.findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(eq(transactionId), any()))
                .thenReturn(Optional.empty());

        assertThatCode(() -> service.supersedeOnEdit(transactionId, distributorId))
                .doesNotThrowAnyException();
    }

    // ---- assertApprovedAndConsume (atomic gate) -----------------------------

    @Test
    void assertApprovedAndConsumePassesAndFlipsApprovedToConsumed() {
        TransactionApprovalChallenge approved = challenge(TransactionApprovalStatus.APPROVED);
        String hash = approved.getSnapshotSha256();
        when(challengeRepository.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
                transactionId, TransactionApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        assertThatCode(() -> service.assertApprovedAndConsume(transactionId, hash))
                .doesNotThrowAnyException();

        // Atomic: the same call that authorized the provider write also burned the challenge.
        assertThat(approved.getStatus()).isEqualTo(TransactionApprovalStatus.CONSUMED);
        assertThat(approved.getConsumedAt()).isNotNull();
        verify(challengeRepository).save(approved);
        verify(auditService).log(anyString(), eq(transactionId), eq("APPROVAL_CONSUMED"), any(), any());
    }

    @Test
    void assertApprovedAndConsumeThrowsWhenNoApprovedChallenge() {
        // A SUPERSEDED/EXPIRED/already-CONSUMED challenge is not APPROVED → not found → blocked.
        when(challengeRepository.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
                transactionId, TransactionApprovalStatus.APPROVED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertApprovedAndConsume(transactionId, "anyhash"))
                .isInstanceOf(IllegalStateException.class);

        verify(challengeRepository, never()).save(any(TransactionApprovalChallenge.class));
    }

    @Test
    void assertApprovedAndConsumeThrowsOnHashMismatchWithoutConsuming() {
        TransactionApprovalChallenge approved = challenge(TransactionApprovalStatus.APPROVED);
        when(challengeRepository.findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
                transactionId, TransactionApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.assertApprovedAndConsume(transactionId, "DIFFERENTHASH"))
                .isInstanceOf(IllegalStateException.class);

        // Hash drift must NOT burn the challenge (re-request, not silently consumed).
        assertThat(approved.getStatus()).isEqualTo(TransactionApprovalStatus.APPROVED);
        verify(challengeRepository, never()).save(any(TransactionApprovalChallenge.class));
    }

    // ---- listPendingForInvestor ---------------------------------------------

    @Test
    void listPendingForInvestorReturnsLiveChallenges() {
        TransactionApprovalChallenge sent = challenge(TransactionApprovalStatus.CHALLENGE_SENT);
        when(challengeRepository.findByInvestorAccountIdAndStatusIn(eq(investorAccountId), any()))
                .thenReturn(List.of(sent));

        List<TransactionApprovalChallenge> result = service.listPendingForInvestor(investorAccountId);

        assertThat(result).hasSize(1).containsExactly(sent);
    }
}

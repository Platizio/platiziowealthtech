package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorActionServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static OrderService passthroughOrderService() {
        OrderService orderService = mock(OrderService.class);
        when(orderService.syncLumpsumOrderFromProvider(any(TransactionOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return orderService;
    }

    @Test
    void confirmPurchaseRunsFpConsentPaymentAndConfirmFlow() throws Exception {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingAuditService auditService = new RecordingAuditService();
        TransactionOrder order = pendingOrder();
        Investor investor = investor(order.getInvestorId());
        ProductScheme scheme = scheme(order.getProductSchemeId());
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        InvestorService investorService = mock(InvestorService.class);
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                bankAccountRepository,
                schemeRepository,
                auditService,
                investorService,
                passthroughOrderService(),
                cybrillaClient,
                "http://localhost/investor-actions/{token}/payment-complete",
                "sandbox",
                "CYBRILLAPOA",
                true,
                true
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(investorService.ensureMfInvestmentAccount(order.getInvestorId())).thenReturn(investor);
        when(schemeRepository.findById(order.getProductSchemeId())).thenReturn(Optional.of(scheme));
        InvestorBankAccount bankAccount = verifiedBankAccount(order.getInvestorId());
        when(bankAccountRepository.findByInvestorId(order.getInvestorId())).thenReturn(List.of(bankAccount));
        when(cybrillaClient.fetchMfPurchase("mfp_123"))
                .thenReturn(json("""
                        {"object":"mf_purchase","id":"mfp_123","old_id":9123,"state":"pending"}
                        """))
                .thenReturn(json("""
                        {"object":"mf_purchase","id":"mfp_123","old_id":9123,"state":"pending"}
                        """))
                .thenReturn(json("""
                        {"object":"mf_purchase","id":"mfp_123","old_id":9123,"state":"submitted"}
                        """));
        when(cybrillaClient.updateMfPurchaseConsent(eq("mfp_123"), any())).thenReturn(json("""
                {"object":"mf_purchase","id":"mfp_123","state":"pending"}
                """));
        when(cybrillaClient.confirmMfPurchase("mfp_123")).thenReturn(json("""
                {"object":"mf_purchase","id":"mfp_123","old_id":9123,"state":"confirmed"}
                """));
        when(cybrillaClient.createUpiUriPayment(
                eq(List.of(9123)),
                eq("http://localhost/investor-actions/action-token/payment-complete"),
                eq(906),
                eq("ONDC")
        )).thenReturn(json("""
                {"id":1,"token_url":null,"upi":{"type":"uri","uri":null}}
                """));
        when(cybrillaClient.fetchPayment(1)).thenReturn(json("""
                {"id":1,"token_url":null,"method":"UPI","upi":{"type":"uri","uri":"upi://pay?pa=billdesk@hdfcbank&am=25000.00&cu=INR"}}
                """));

        InvestorActionService.InvestorActionPage page = service.confirmPurchase("action-token");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMode()).isEqualTo("UPI");
        assertThat(order.getInvestorActionUrl()).startsWith("upi://pay");
        assertThat(page.confirmationAllowed()).isFalse();
        assertThat(page.paymentRedirectUrl()).startsWith("upi://pay");
        assertThat(page.message()).contains("Open your UPI app");
        verify(cybrillaClient).updateMfPurchaseConsent(eq("mfp_123"), any());
        verify(cybrillaClient).createUpiUriPayment(
                eq(List.of(9123)),
                eq("http://localhost/investor-actions/action-token/payment-complete"),
                eq(906),
                eq("ONDC")
        );
        verify(cybrillaClient).confirmMfPurchase("mfp_123");
        assertThat(auditService.actionType.get()).isEqualTo("INVESTOR_ACTION_CONFIRMED");
    }

    @Test
    void confirmPurchaseResumesExistingPaymentRedirectWithoutCallingProvider() throws Exception {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingAuditService auditService = new RecordingAuditService();
        TransactionOrder order = pendingOrder();
        order.setOrderStatus(OrderStatus.PAYMENT_PENDING);
        order.setInvestorActionUrl("https://payments.fp/existing-token");
        Investor investor = investor(order.getInvestorId());
        InvestorService investorService = mock(InvestorService.class);
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                mock(InvestorBankAccountRepository.class),
                schemeRepository,
                auditService,
                investorService,
                passthroughOrderService(),
                cybrillaClient,
                "http://localhost/investor-actions/{token}/payment-complete",
                "sandbox",
                "CYBRILLAPOA",
                true,
                true
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(investorService.ensureMfInvestmentAccount(order.getInvestorId())).thenReturn(investor);
        when(schemeRepository.findById(order.getProductSchemeId())).thenReturn(Optional.of(scheme(order.getProductSchemeId())));
        when(cybrillaClient.fetchMfPurchase("mfp_123")).thenReturn(json("""
                {"object":"mf_purchase","id":"mfp_123","state":"submitted"}
                """));

        InvestorActionService.InvestorActionPage page = service.confirmPurchase("action-token");

        assertThat(page.paymentRedirectUrl()).isEqualTo("https://payments.fp/existing-token");
        verify(cybrillaClient, org.mockito.Mockito.times(1)).fetchMfPurchase("mfp_123");
        verify(cybrillaClient, org.mockito.Mockito.never()).updateMfPurchaseConsent(org.mockito.ArgumentMatchers.anyString(), any());
    }

    @Test
    void getPageUsesOrderSchemeSnapshotWhenSchemeRowIsMissing() {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        TransactionOrder order = pendingOrder();
        order.setExternalOrderId(null);
        order.setProductSchemeName("HDFC Balanced Advantage Fund");
        order.setProductSchemeAmcName("HDFC Mutual Fund");
        Investor investor = investor(order.getInvestorId());
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                mock(InvestorBankAccountRepository.class),
                schemeRepository,
                new RecordingAuditService(),
                mock(InvestorService.class),
                passthroughOrderService(),
                mock(CybrillaClient.class),
                "http://localhost/investor-actions/{token}/payment-complete",
                "sandbox",
                "CYBRILLAPOA",
                true,
                true
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(schemeRepository.findById(order.getProductSchemeId())).thenReturn(Optional.empty());

        InvestorActionService.InvestorActionPage page = service.getPage("action-token");

        assertThat(page.schemeName()).isEqualTo("HDFC Balanced Advantage Fund");
        assertThat(page.amcName()).isEqualTo("HDFC Mutual Fund");
    }

    @Test
    void simulateSandboxMandateApprovalAdvancesMandateAndSubmitsPlan() throws Exception {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingAuditService auditService = new RecordingAuditService();
        InvestorService investorService = mock(InvestorService.class);
        TransactionOrder order = pendingSipOrder();
        order.setOrderStatus(OrderStatus.PAYMENT_PENDING);
        order.setExternalMandateId(3001);
        order.setMandateStatus("AUTH_PENDING");
        Investor investor = investor(order.getInvestorId());
        ProductScheme scheme = scheme(order.getProductSchemeId());
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                bankAccountRepository,
                schemeRepository,
                auditService,
                investorService,
                passthroughOrderService(),
                cybrillaClient,
                "http://localhost/investor-actions/{token}/payment-complete",
                "sandbox",
                "CYBRILLAPOA",
                true,
                true
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(investorService.ensureMfInvestmentAccount(order.getInvestorId())).thenReturn(investor);
        when(schemeRepository.findById(order.getProductSchemeId())).thenReturn(Optional.of(scheme));
        when(cybrillaClient.fetchMandate(3001))
                .thenReturn(json("{\"id\":3001,\"mandate_status\":\"CREATED\"}"))
                .thenReturn(json("{\"id\":3001,\"mandate_status\":\"SUBMITTED\"}"))
                .thenReturn(json("{\"id\":3001,\"mandate_status\":\"RECEIVED\"}"))
                .thenReturn(json("{\"id\":3001,\"mandate_status\":\"APPROVED\"}"));
        when(cybrillaClient.createSipOrderWithMandate(org.mockito.ArgumentMatchers.eq(order), org.mockito.ArgumentMatchers.eq(investor), org.mockito.ArgumentMatchers.eq(scheme), org.mockito.ArgumentMatchers.eq(3001)))
                .thenReturn("mfpp_123");
        when(cybrillaClient.fetchMfPurchasePlan("mfpp_123"))
                .thenReturn(json("{\"id\":\"mfpp_123\",\"state\":\"review_completed\"}"));
        when(cybrillaClient.updateMfPurchasePlan(org.mockito.ArgumentMatchers.eq("mfpp_123"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(json("{\"id\":\"mfpp_123\",\"state\":\"confirmed\"}"));
        when(cybrillaClient.listMfPurchasesForPlan("mfpp_123"))
                .thenReturn(json("{\"data\":[{\"old_id\":8801}]}"));
        when(cybrillaClient.createNachPayment(3001, List.of(8801)))
                .thenReturn(json("{\"id\":9901}"));

        InvestorActionService.InvestorActionPage page = service.simulateSandboxMandateApproval("action-token");

        assertThat(order.getMandateStatus()).isEqualTo("APPROVED");
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(page.message()).contains("SIP mandate setup is complete");
        verify(cybrillaClient).simulateMandate(3001, "SUBMITTED");
        verify(cybrillaClient).simulateMandate(3001, "RECEIVED");
        verify(cybrillaClient).simulateMandate(3001, "APPROVED");
    }

    @Test
    void confirmSipMandateStartsAuthorizationRedirect() throws Exception {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingAuditService auditService = new RecordingAuditService();
        TransactionOrder order = pendingSipOrder();
        Investor investor = investor(order.getInvestorId());
        InvestorBankAccount bankAccount = verifiedBankAccount(order.getInvestorId());
        InvestorService investorService = mock(InvestorService.class);
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                bankAccountRepository,
                schemeRepository,
                auditService,
                investorService,
                passthroughOrderService(),
                cybrillaClient,
                "http://localhost/investor-actions/{token}/payment-complete",
                "sandbox",
                "CYBRILLAPOA",
                true,
                true
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(investorService.ensureMfInvestmentAccount(order.getInvestorId())).thenReturn(investor);
        when(bankAccountRepository.findByInvestorId(order.getInvestorId())).thenReturn(List.of(bankAccount));
        when(cybrillaClient.createMandate(eq(906), eq("E_MANDATE"), eq(100_000), eq("CYBRILLAPOA")))
                .thenReturn(json("{\"id\":3001,\"mandate_status\":\"CREATED\"}"));
        when(cybrillaClient.authorizeMandate(eq(3001), eq("http://localhost/investor-actions/action-token/payment-complete")))
                .thenReturn(json("{\"id\":4001,\"token_url\":\"https://payments.fp/mandate-auth\"}"));

        InvestorActionService.InvestorActionPage page = service.confirmPurchase("action-token");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getExternalMandateId()).isEqualTo(3001);
        assertThat(order.getInvestorActionUrl()).isEqualTo("https://payments.fp/mandate-auth");
        assertThat(page.paymentRedirectUrl()).isEqualTo("https://payments.fp/mandate-auth");
        assertThat(auditService.actionType.get()).isEqualTo("SIP_MANDATE_AUTH_STARTED");
    }

    private com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception {
        return OBJECT_MAPPER.readTree(value);
    }

    private TransactionOrder pendingSipOrder() {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setInvestorActionToken("action-token");
        order.setInvestorId(UUID.randomUUID());
        order.setDistributorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setTransactionType(TransactionType.SIP);
        order.setAmount(new BigDecimal("5000.00"));
        order.setPaymentMode("MANDATE");
        order.setMandateMode("AUTO_DEBIT");
        order.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        return order;
    }

    private InvestorBankAccount verifiedBankAccount(UUID investorId) {
        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", UUID.randomUUID());
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);
        bankAccount.setCybrillaBankId("bac_test");
        bankAccount.setFpBankAccountOldId(906);
        bankAccount.setAccountHolderName("Riya Shah");
        bankAccount.setAccountNumber("1234567890");
        bankAccount.setIfscCode("HDFC0000001");
        return bankAccount;
    }

    private TransactionOrder pendingOrder() {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setInvestorActionToken("action-token");
        order.setInvestorId(UUID.randomUUID());
        order.setDistributorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setTransactionType(TransactionType.LUMPSUM_PURCHASE);
        order.setAmount(new BigDecimal("25000.00"));
        order.setPaymentMode("BANK_TRANSFER");
        order.setExternalOrderId("mfp_123");
        order.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        return order;
    }

    private Investor investor(UUID investorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setFullName("Riya Shah");
        investor.setEmail("riya@example.com");
        investor.setMobileNumber("9811100001");
        return investor;
    }

    private ProductScheme scheme(UUID schemeId) {
        ProductScheme scheme = new ProductScheme();
        ReflectionTestUtils.setField(scheme, "id", schemeId);
        scheme.setSchemeName("Focused Equity Fund");
        scheme.setAmcName("Platizio AMC");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode("MF-FOCUSED");
        return scheme;
    }

    private static class RecordingAuditService extends AuditService {
        private final AtomicReference<String> actionType = new AtomicReference<>();

        RecordingAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            this.actionType.set(actionType);
        }
    }
}

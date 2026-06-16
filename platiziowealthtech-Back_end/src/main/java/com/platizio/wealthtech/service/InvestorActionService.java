package com.platizio.wealthtech.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InvestorActionService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorActionService.class);
    private static final ObjectMapper DEBUG_MAPPER = new ObjectMapper();
    private static final int MF_PURCHASE_REVIEW_POLL_ATTEMPTS = 24;
    private static final int MF_PURCHASE_SUBMIT_POLL_ATTEMPTS = 20;
    private static final int MF_PURCHASE_PLAN_REVIEW_POLL_ATTEMPTS = 12;
    private static final long MF_PURCHASE_REVIEW_POLL_INTERVAL_MS = 1_000L;
    private static final String MANDATE_STATUS_APPROVED = "APPROVED";
    private static final int PURCHASE_FINALIZE_POLL_ATTEMPTS = 12;

    private final TransactionOrderRepository orderRepository;
    private final InvestorRepository investorRepository;
    private final InvestorBankAccountRepository bankAccountRepository;
    private final ProductSchemeRepository schemeRepository;
    private final AuditService auditService;
    private final InvestorService investorService;
    private final OrderService orderService;
    private final CybrillaClient cybrillaClient;
    private final String paymentPostbackUrl;
    private final String cybrillaEnvironment;
    private final String mandateProviderName;
    private final boolean sandboxSimulateMandateApproval;
    private final boolean sandboxSimulatePayment;

    public InvestorActionService(
            TransactionOrderRepository orderRepository,
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            ProductSchemeRepository schemeRepository,
            AuditService auditService,
            InvestorService investorService,
            OrderService orderService,
            CybrillaClient cybrillaClient,
            @Value("${app.payment.postback-url:}") String paymentPostbackUrl,
            @Value("${cybrilla.environment:sandbox}") String cybrillaEnvironment,
            @Value("${cybrilla.payment.mandate-provider-name:CYBRILLAPOA}") String mandateProviderName,
            @Value("${cybrilla.payment.sandbox-simulate-mandate-approval:true}") boolean sandboxSimulateMandateApproval,
            @Value("${cybrilla.payment.sandbox-simulate-payment:true}") boolean sandboxSimulatePayment
    ) {
        this.orderRepository = orderRepository;
        this.investorRepository = investorRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.schemeRepository = schemeRepository;
        this.auditService = auditService;
        this.investorService = investorService;
        this.orderService = orderService;
        this.cybrillaClient = cybrillaClient;
        this.paymentPostbackUrl = paymentPostbackUrl;
        this.cybrillaEnvironment = cybrillaEnvironment;
        this.mandateProviderName = mandateProviderName;
        this.sandboxSimulateMandateApproval = sandboxSimulateMandateApproval;
        this.sandboxSimulatePayment = sandboxSimulatePayment;
    }

    @Transactional
    public InvestorActionPage getPage(String token) {
        TransactionOrder order = findOrder(token);
        order = orderService.syncLumpsumOrderFromProvider(order);
        return pageFor(order, messageFor(order));
    }

    @Transactional
    public InvestorActionPage confirmPurchase(String token) {
        TransactionOrder order = findOrder(token);
        order = orderService.syncLumpsumOrderFromProvider(order);
        String message;
        if (order.getOrderStatus() == OrderStatus.PENDING_INVESTOR_ACTION
                || order.getOrderStatus() == OrderStatus.PROCESSING) {
            Investor investor = investorRepository.findById(order.getInvestorId())
                    .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
            try {
                investor = investorService.ensureMfInvestmentAccount(investor.getId());
                if (isSipMandateOrder(order)) {
                    message = confirmSipMandatePurchase(order, investor);
                } else {
                    String paymentRedirectUrl = submitPurchaseForPayment(order, investor);
                    order.setOrderStatus(OrderStatus.PAYMENT_PENDING);
                    order.setInvestorActionUrl(paymentRedirectUrl);
                    order = orderRepository.save(order);
                    auditService.log(
                            "ORDER",
                            order.getId(),
                            "INVESTOR_ACTION_CONFIRMED",
                            order.getDistributorId(),
                            "{\"status\":\"PAYMENT_PENDING\",\"paymentRedirect\":\"provider\"}"
                    );
                    message = isUpiUri(paymentRedirectUrl)
                            ? "Purchase confirmed. Open your UPI app to complete your investment."
                            : "Purchase confirmed. Continue to the payment page to complete your investment.";
                }
            } catch (CybrillaUnavailableException ex) {
                logger.warn(
                        "investor_action_confirm status='provider_unreachable' order_id='{}' reason='{}'",
                        order.getId(),
                        ex.getMessage()
                );
                message = "Cybrilla/Fintech Primitives is temporarily unreachable. "
                        + "Please wait a moment and click Confirm Purchase again.";
            } catch (IllegalStateException ex) {
                logger.warn(
                        "investor_action_confirm status='investor_not_ready' order_id='{}' reason='{}'",
                        order.getId(),
                        ex.getMessage()
                );
                message = ex.getMessage();
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "investor_action_confirm status='failed' order_id='{}' reason='{}'",
                        order.getId(),
                        ex.getMessage()
                );
                if (syncLocalOrderForProviderPurchaseFailure(order, ex.getMessage())) {
                    order = orderRepository.save(order);
                }
                message = providerFailureMessage(ex, "Unable to start payment right now. Please try again in a few minutes.");
            }
        } else {
            message = messageFor(order);
        }
        return pageFor(order, message);
    }

    @Transactional
    public InvestorActionPage handlePaymentPostback(String token, String paymentId, String status) {
        TransactionOrder order = findOrder(token);
        String message;
        if (!isSipMandateOrder(order)) {
            message = reconcileLumpsumPaymentPostback(order, paymentId, status);
        } else if (!MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())) {
            message = reconcileSipMandateAuthPostback(order, paymentId, status);
        } else {
            message = messageFor(order);
        }
        return pageFor(orderRepository.save(order), message);
    }

    @Transactional
    public InvestorActionPage simulateSandboxPayment(String token) {
        requireSandboxPaymentSimulation();
        TransactionOrder order = findOrder(token);
        if (order.getOrderStatus() != OrderStatus.PAYMENT_PENDING) {
            return pageFor(order, "Payment simulation is only available while payment is pending.");
        }
        if (isSipMandateOrder(order) && !MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())) {
            return pageFor(order, "Use mandate simulation for SIP mandate authorization (sandbox).");
        }
        Integer fpPaymentId = order.getExternalPaymentId();
        if (fpPaymentId != null && fpPaymentId > 0) {
            try {
                advancePaymentToSuccessForSandbox(fpPaymentId);
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "investor_action_payment_simulate status='provider_simulate_failed' order_id='{}' payment_id='{}' reason='{}'",
                        order.getId(),
                        fpPaymentId,
                        ex.getMessage()
                );
            }
        }
        String paymentId = fpPaymentId == null || fpPaymentId <= 0 ? "sandbox" : String.valueOf(fpPaymentId);
        return handlePaymentPostback(token, paymentId, "success");
    }

    @Transactional
    public InvestorActionPage prepareInvestorForRetry(String token) {
        TransactionOrder order = findOrder(token);
        try {
            investorService.repairInvestorForFpOrders(order.getInvestorId());
            return pageFor(
                    order,
                    "Investor profile, bank verification, and MF account were refreshed with Fintech Primitives. "
                            + "This failed purchase cannot be retried — place a new lumpsum from Ledger "
                            + "(use amount ending in 0, e.g. ₹5000).");
        } catch (IllegalStateException | CybrillaApiException ex) {
            logger.warn(
                    "investor_action_prepare status='failed' order_id='{}' reason='{}'",
                    order.getId(),
                    ex.getMessage()
            );
            return pageFor(order, "Could not refresh investor with Fintech Primitives: " + ex.getMessage());
        }
    }

    @Transactional
    public InvestorActionPage simulateSandboxMandateApproval(String token) {
        requireSandboxMandateSimulation();
        TransactionOrder order = findOrder(token);
        if (!sandboxMandateSimulationAllowed(order)) {
            return pageFor(order, "Mandate simulation is only available while SIP mandate authorization is pending.");
        }
        int mandateId = order.getExternalMandateId() == null ? 0 : order.getExternalMandateId();
        try {
            advanceMandateToApprovedForSandbox(mandateId);
            order.setMandateStatus(MANDATE_STATUS_APPROVED);
            Investor investor = investorRepository.findById(order.getInvestorId())
                    .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
            investor = investorService.ensureMfInvestmentAccount(investor.getId());
            String message = submitSipPlanAfterApprovedMandate(order, investor);
            order = orderRepository.save(order);
            return pageFor(order, message);
        } catch (CybrillaApiException | IllegalStateException ex) {
            logger.warn(
                    "investor_action_mandate_simulate status='failed' order_id='{}' mandate_id='{}' reason='{}'",
                    order.getId(),
                    mandateId,
                    ex.getMessage()
            );
            return pageFor(orderRepository.save(order), "Mandate setup could not be completed: " + ex.getMessage());
        }
    }

    public boolean sandboxPaymentSimulationEnabled() {
        return sandboxSimulatePayment && "sandbox".equalsIgnoreCase(cybrillaEnvironment);
    }

    public boolean sandboxMandateSimulationEnabled() {
        return sandboxSimulateMandateApproval && "sandbox".equalsIgnoreCase(cybrillaEnvironment);
    }

    private String confirmSipMandatePurchase(TransactionOrder order, Investor investor) {
        if (MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())
                && order.getExternalMandateId() != null
                && order.getExternalMandateId() > 0) {
            return submitSipPlanAfterApprovedMandate(order, investor);
        }
        return startSipMandateAuthorization(order, investor);
    }

    private String startSipMandateAuthorization(TransactionOrder order, Investor investor) {
        int bankAccountOldId = resolveFpBankAccountOldId(investor);
        int mandateId = order.getExternalMandateId() == null ? 0 : order.getExternalMandateId();
        if (mandateId <= 0) {
            int mandateLimit = mandateLimitFor(order.getAmount());
            JsonNode mandate = cybrillaClient.createMandate(
                    bankAccountOldId,
                    externalMandateType(order.getMandateMode()),
                    mandateLimit,
                    mandateProviderName
            );
            mandateId = mandate.path("id").asInt(0);
            if (mandateId <= 0) {
                throw new CybrillaApiException("Fintech Primitives mandate response did not include an id");
            }
            order.setExternalMandateId(mandateId);
            order.setMandateStatus(firstText(mandate, "mandate_status", "CREATED"));
        }

        JsonNode auth = cybrillaClient.authorizeMandate(mandateId, paymentPostbackUrlFor(order.getInvestorActionToken()));
        String tokenUrl = auth.path("token_url").asText(null);
        if (!StringUtils.hasText(tokenUrl)) {
            throw new CybrillaApiException("Fintech Primitives mandate authorization response did not include a token_url");
        }
        order.setOrderStatus(OrderStatus.PAYMENT_PENDING);
        order.setInvestorActionUrl(tokenUrl);
        order.setMandateStatus("AUTH_PENDING");
        orderRepository.save(order);
        auditService.log(
                "ORDER",
                order.getId(),
                "SIP_MANDATE_AUTH_STARTED",
                order.getDistributorId(),
                "{\"mandateId\":" + mandateId + "}"
        );
        return "Authorize your SIP mandate on the secure page, then return here to finish setup.";
    }

    private String reconcileSipMandateAuthPostback(TransactionOrder order, String paymentId, String status) {
        if (StringUtils.hasText(status) && !"success".equalsIgnoreCase(status.trim())) {
            order.setFailureReason("Mandate authorization failed"
                    + (StringUtils.hasText(paymentId) ? " (paymentId=" + paymentId + ")" : ""));
            order.setOrderStatus(OrderStatus.FAILED);
            return "Mandate authorization failed. Please contact your distributor to retry.";
        }
        int mandateId = order.getExternalMandateId() == null ? 0 : order.getExternalMandateId();
        if (mandateId <= 0) {
            throw new CybrillaApiException("Order does not have a mandate id to reconcile");
        }
        awaitApprovedMandate(order, mandateId);
        Investor investor = investorRepository.findById(order.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        return submitSipPlanAfterApprovedMandate(order, investor);
    }

    private void awaitApprovedMandate(TransactionOrder order, int mandateId) {
        if (sandboxMandateSimulationEnabled()) {
            advanceMandateToApprovedForSandbox(mandateId);
        }
        JsonNode mandate = cybrillaClient.fetchMandate(mandateId);
        String mandateStatus = mandate.path("mandate_status").asText("");
        if (!MANDATE_STATUS_APPROVED.equalsIgnoreCase(mandateStatus)) {
            throw new CybrillaApiException("Mandate is not approved yet (status='" + mandateStatus + "')");
        }
        order.setMandateStatus(MANDATE_STATUS_APPROVED);
    }

    /**
     * FP sandbox mandate simulation accepts CREATED → SUBMITTED → RECEIVED → APPROVED transitions
     * ({@code POST /api/pg/simulate/mandates/{id}}). A single APPROVED jump often leaves the mandate stuck.
     */
    private void advanceMandateToApprovedForSandbox(int mandateId) {
        if (mandateId <= 0) {
            throw new CybrillaApiException("Mandate id is required before sandbox simulation");
        }
        JsonNode mandate = cybrillaClient.fetchMandate(mandateId);
        String status = mandate.path("mandate_status").asText("");
        if (MANDATE_STATUS_APPROVED.equalsIgnoreCase(status)) {
            return;
        }
        for (String step : List.of("SUBMITTED", "RECEIVED", MANDATE_STATUS_APPROVED)) {
            if (MANDATE_STATUS_APPROVED.equalsIgnoreCase(status)) {
                break;
            }
            try {
                cybrillaClient.simulateMandate(mandateId, step);
            } catch (CybrillaApiException ex) {
                logger.info(
                        "sip_mandate_simulate step='{}' mandate_id='{}' note='{}'",
                        step,
                        mandateId,
                        ex.getMessage()
                );
            }
            mandate = cybrillaClient.fetchMandate(mandateId);
            status = mandate.path("mandate_status").asText("");
        }
        if (!MANDATE_STATUS_APPROVED.equalsIgnoreCase(status)) {
            throw new CybrillaApiException(
                    "Mandate is not approved after sandbox simulation (last_status='" + status + "')"
            );
        }
    }

    private String submitSipPlanAfterApprovedMandate(TransactionOrder order, Investor investor) {
        ProductScheme scheme = schemeRepository.findById(order.getProductSchemeId())
                .orElseThrow(() -> new EntityNotFoundException("Product scheme not found"));
        int mandateId = order.getExternalMandateId() == null ? 0 : order.getExternalMandateId();
        if (mandateId <= 0) {
            throw new CybrillaApiException("Approved mandate id is required before SIP plan submission");
        }

        if (!StringUtils.hasText(order.getExternalOrderId())) {
            String planId = cybrillaClient.createSipOrderWithMandate(order, investor, scheme, mandateId);
            order.setExternalOrderId(planId);
            orderRepository.save(order);
        }

        JsonNode plan = awaitMfPurchasePlanReviewCompleted(order.getExternalOrderId());
        Map<String, Object> confirmPayload = new LinkedHashMap<>();
        confirmPayload.put("state", "confirmed");
        confirmPayload.put("consent", consentPayload(investor));
        cybrillaClient.updateMfPurchasePlan(order.getExternalOrderId(), confirmPayload);

        int installmentAmcOrderId = awaitFirstInstallmentAmcOrderId(order.getExternalOrderId());
        JsonNode nachPayment = cybrillaClient.createNachPayment(mandateId, List.of(installmentAmcOrderId));
        order.setExternalPaymentId(nachPayment.path("id").asInt(0));
        order.setOrderStatus(OrderStatus.ACTIVE);
        order.setInvestorActionUrl(null);
        orderRepository.save(order);
        auditService.log(
                "ORDER",
                order.getId(),
                "SIP_MANDATE_PLAN_SUBMITTED",
                order.getDistributorId(),
                "{\"planId\":\"" + order.getExternalOrderId() + "\",\"mandateId\":" + mandateId
                        + ",\"paymentId\":" + order.getExternalPaymentId() + "}"
        );
        String planState = plan.path("state").asText("submitted");
        return "SIP mandate setup is complete. Your plan is " + planState.replace('_', ' ') + " and the first installment debit has been initiated.";
    }

    private int firstInstallmentAmcOrderId(String planId) {
        JsonNode purchases = cybrillaClient.listMfPurchasesForPlan(planId);
        JsonNode data = purchases.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new CybrillaApiException("No installments were generated for the SIP plan yet");
        }
        int amcOrderId = data.get(0).path("old_id").asInt(0);
        if (amcOrderId <= 0) {
            throw new CybrillaApiException("First SIP installment did not include an AMC order id");
        }
        return amcOrderId;
    }

    private int awaitFirstInstallmentAmcOrderId(String planId) {
        CybrillaApiException lastError = null;
        for (int attempt = 1; attempt <= MF_PURCHASE_PLAN_REVIEW_POLL_ATTEMPTS; attempt++) {
            try {
                return firstInstallmentAmcOrderId(planId);
            } catch (CybrillaApiException ex) {
                lastError = ex;
                if (!ex.getMessage().contains("No installments")) {
                    throw ex;
                }
                sleepReviewPollInterval();
            }
        }
        throw lastError == null
                ? new CybrillaApiException("No installments were generated for the SIP plan yet")
                : lastError;
    }

    private JsonNode awaitMfPurchasePlanReviewCompleted(String planId) {
        JsonNode latest = null;
        String state = null;
        for (int attempt = 1; attempt <= MF_PURCHASE_PLAN_REVIEW_POLL_ATTEMPTS; attempt++) {
            latest = cybrillaClient.fetchMfPurchasePlan(planId);
            state = latest.path("state").asText("");
            if ("review_completed".equalsIgnoreCase(state) || "active".equalsIgnoreCase(state) || "confirmed".equalsIgnoreCase(state)) {
                return latest;
            }
            if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)) {
                throw new CybrillaApiException("SIP plan is " + state + " and cannot be confirmed");
            }
            if ("created".equalsIgnoreCase(state) || "under_review".equalsIgnoreCase(state)) {
                sleepReviewPollInterval();
                continue;
            }
            sleepReviewPollInterval();
        }
        throw new CybrillaApiException(
                "SIP plan review is still in progress (last_state='" + state + "'). Please try again shortly."
        );
    }

    private String reconcileLumpsumPaymentPostback(TransactionOrder order, String paymentId, String status) {
        if (StringUtils.hasText(status) && "success".equalsIgnoreCase(status.trim())) {
            order.setOrderStatus(OrderStatus.SUBMITTED);
            if (order.getExternalPaymentId() == null || order.getExternalPaymentId() <= 0) {
                order.setExternalPaymentId(parsePaymentId(paymentId));
            }
            try {
                finalizeLumpsumPurchase(order);
            } catch (CybrillaUnavailableException ex) {
                logger.warn(
                        "investor_action_payment_finalize status='provider_unreachable' order_id='{}' reason='{}'",
                        order.getId(),
                        ex.getMessage()
                );
                if (sandboxPaymentSimulationEnabled()) {
                    order.setOrderStatus(OrderStatus.SUCCESSFUL);
                    order.setInvestorActionUrl(null);
                }
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "investor_action_payment_finalize status='failed' order_id='{}' reason='{}'",
                        order.getId(),
                        ex.getMessage()
                );
                if (sandboxPaymentSimulationEnabled()) {
                    order.setOrderStatus(OrderStatus.SUCCESSFUL);
                    order.setInvestorActionUrl(null);
                }
            }
            if (order.getOrderStatus() == OrderStatus.SUCCESSFUL) {
                auditService.log(
                        "ORDER",
                        order.getId(),
                        "PURCHASE_SUCCESSFUL",
                        order.getDistributorId(),
                        "{\"externalOrderId\":\"" + safe(order.getExternalOrderId()) + "\"}"
                );
                return "Payment received. Your purchase is complete and will appear in the portfolio.";
            }
            return "Payment received. Your purchase is being submitted to the provider.";
        }
        if (StringUtils.hasText(status) && "pending".equalsIgnoreCase(status.trim())) {
            return "Payment is pending provider confirmation.";
        }
        order.setOrderStatus(OrderStatus.FAILED);
        order.setFailureReason("Payment failed"
                + (StringUtils.hasText(paymentId) ? " (paymentId=" + paymentId + ")" : ""));
        return "Payment failed. Please contact your distributor to retry.";
    }

    private String submitPurchaseForPayment(TransactionOrder order, Investor investor) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new CybrillaApiException("Order does not have a Fintech Primitives purchase id yet");
        }

        String existingRedirect = existingPaymentRedirectUrl(order);
        if (existingRedirect != null) {
            return existingRedirect;
        }

        JsonNode purchase = awaitMfPurchaseReviewPassed(order.getExternalOrderId());
        String state = purchase.path("state").asText("");

        // Payment retry — order already submitted; create a fresh payment only.
        if ("submitted".equalsIgnoreCase(state)) {
            return createLumpsumPaymentRedirect(order, investor, purchase);
        }

        if ("pending".equalsIgnoreCase(state)) {
            // Cybrillapoa / ONDC gateway (FP Create MF Purchase + custom-checkout docs):
            // consent -> create payment -> confirm -> submitted -> UPI URI or hosted token_url.
            cybrillaClient.updateMfPurchaseConsent(order.getExternalOrderId(), consentPayload(investor));
            purchase = cybrillaClient.fetchMfPurchase(order.getExternalOrderId());
            JsonNode payment = createLumpsumPayment(order, investor, purchase);
            cybrillaClient.confirmMfPurchase(order.getExternalOrderId());
            purchase = awaitMfPurchaseSubmitted(order.getExternalOrderId());
            return resolvePaymentRedirectUrl(payment, order.getExternalPaymentId());
        }

        if ("confirmed".equalsIgnoreCase(state)) {
            purchase = awaitMfPurchaseSubmitted(order.getExternalOrderId());
            return createLumpsumPaymentRedirect(order, investor, purchase);
        }

        throw new CybrillaApiException(
                "Purchase order is in state '" + state + "' and cannot proceed to payment"
        );
    }

    private String createLumpsumPaymentRedirect(TransactionOrder order, Investor investor, JsonNode purchase) {
        JsonNode payment = createLumpsumPayment(order, investor, purchase);
        return resolvePaymentRedirectUrl(payment, order.getExternalPaymentId());
    }

    private JsonNode createLumpsumPayment(TransactionOrder order, Investor investor, JsonNode purchase) {
        int amcOrderId = purchase.path("old_id").asInt(0);
        if (amcOrderId <= 0) {
            throw new CybrillaApiException("Fintech Primitives purchase response did not include an AMC order id");
        }
        int bankAccountOldId = resolveFpBankAccountOldId(investor);
        String method = paymentMethod(order.getPaymentMode());
        if (!method.equals(order.getPaymentMode())) {
            order.setPaymentMode(method);
        }
        JsonNode payment = "UPI".equals(method)
                ? cybrillaClient.createUpiUriPayment(
                        List.of(amcOrderId),
                        paymentPostbackUrlFor(order.getInvestorActionToken()),
                        bankAccountOldId,
                        "ONDC"
                )
                : cybrillaClient.createNetbankingPayment(
                        List.of(amcOrderId),
                        paymentPostbackUrlFor(order.getInvestorActionToken()),
                        method,
                        bankAccountOldId,
                        "ONDC"
                );
        int paymentRecordId = payment.path("id").asInt(0);
        if (paymentRecordId > 0) {
            order.setExternalPaymentId(paymentRecordId);
        }
        return payment;
    }

    private String resolvePaymentRedirectUrl(JsonNode payment, Integer paymentId) {
        String upiUri = upiUri(payment);
        if (StringUtils.hasText(upiUri)) {
            return upiUri;
        }
        String tokenUrl = payment == null ? null : payment.path("token_url").asText(null);
        if (StringUtils.hasText(tokenUrl)) {
            return tokenUrl;
        }
        if (paymentId != null && paymentId > 0) {
            JsonNode latest = cybrillaClient.fetchPayment(paymentId);
            upiUri = upiUri(latest);
            if (StringUtils.hasText(upiUri)) {
                return upiUri;
            }
            tokenUrl = latest.path("token_url").asText(null);
            if (StringUtils.hasText(tokenUrl)) {
                return tokenUrl;
            }
        }
        if (sandboxPaymentSimulationEnabled()) {
            return null;
        }
        throw new CybrillaApiException("Fintech Primitives payment response did not include a token_url or UPI URI");
    }

    private String upiUri(JsonNode payment) {
        if (payment == null || payment.isMissingNode() || payment.isNull()) {
            return null;
        }
        String uri = payment.path("upi").path("uri").asText(null);
        return StringUtils.hasText(uri) ? uri : null;
    }

    /**
     * FP sandbox: {@code POST /api/pg/simulate/payments/{id}} with SUCCESS (and intermediate states if needed).
     */
    private void advancePaymentToSuccessForSandbox(int paymentId) {
        for (String step : List.of("SUBMITTED", "APPROVED", "SUCCESS")) {
            try {
                cybrillaClient.simulatePayment(paymentId, step);
            } catch (CybrillaApiException ex) {
                logger.info(
                        "investor_action_payment_simulate step='{}' payment_id='{}' note='{}'",
                        step,
                        paymentId,
                        ex.getMessage()
                );
            }
        }
    }

    private String existingPaymentRedirectUrl(TransactionOrder order) {
        if (order.getOrderStatus() != OrderStatus.PAYMENT_PENDING) {
            return null;
        }
        String actionUrl = order.getInvestorActionUrl();
        if (StringUtils.hasText(actionUrl) && (actionUrl.startsWith("http") || isUpiUri(actionUrl))) {
            return actionUrl;
        }
        return null;
    }

    /**
     * FP cybrillapoa review: {@code under_review} → {@code pending}. See payment-retry guide.
     */
    private JsonNode awaitMfPurchaseReviewPassed(String externalOrderId) {
        JsonNode latest = null;
        String state = null;
        for (int attempt = 1; attempt <= MF_PURCHASE_REVIEW_POLL_ATTEMPTS; attempt++) {
            latest = cybrillaClient.fetchMfPurchase(externalOrderId);
            state = latest.path("state").asText("");
            if ("pending".equalsIgnoreCase(state)
                    || "confirmed".equalsIgnoreCase(state)
                    || "submitted".equalsIgnoreCase(state)) {
                return latest;
            }
            if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)) {
                throw purchaseTerminalStateException(state, latest);
            }
            if ("under_review".equalsIgnoreCase(state)) {
                sleepReviewPollInterval();
                continue;
            }
            logger.warn(
                    "investor_action_review_poll unexpected_state='{}' external_order_id='{}' attempt='{}/{}'",
                    state,
                    externalOrderId,
                    attempt,
                    MF_PURCHASE_REVIEW_POLL_ATTEMPTS
            );
            sleepReviewPollInterval();
        }
        throw new CybrillaApiException(
                "Purchase order review is still in progress (last_state='" + state + "'). Please try again shortly."
        );
    }

    /**
     * After consent + confirm, FP submits the purchase to cybrillapoa ({@code submitted}) before payment creation.
     */
    private JsonNode awaitMfPurchaseSubmitted(String externalOrderId) {
        JsonNode latest = null;
        String state = null;
        for (int attempt = 1; attempt <= MF_PURCHASE_SUBMIT_POLL_ATTEMPTS; attempt++) {
            latest = cybrillaClient.fetchMfPurchase(externalOrderId);
            state = latest.path("state").asText("");
            if ("submitted".equalsIgnoreCase(state)) {
                return latest;
            }
            if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)) {
                throw purchaseTerminalStateException(state, latest);
            }
            if ("confirmed".equalsIgnoreCase(state) || "pending".equalsIgnoreCase(state)) {
                sleepReviewPollInterval();
                continue;
            }
            logger.warn(
                    "investor_action_submit_poll unexpected_state='{}' external_order_id='{}' attempt='{}/{}'",
                    state,
                    externalOrderId,
                    attempt,
                    MF_PURCHASE_SUBMIT_POLL_ATTEMPTS
            );
            sleepReviewPollInterval();
        }
        throw new CybrillaApiException(
                "Purchase order submission is still in progress (last_state='" + state + "'). Please try again shortly."
        );
    }

    private CybrillaApiException purchaseTerminalStateException(String state, JsonNode purchase) {
        String providerReason = mfPurchaseFailureReason(purchase);
        String message = "Purchase order is " + state + " and cannot be confirmed";
        if (StringUtils.hasText(providerReason)) {
            message += ". Provider reason: " + providerReason;
        } else if (sandboxPaymentSimulationEnabled()) {
            message += ". Sandbox tip: use an amount ending in 0 (e.g. ₹5000) for a successful review, or ending in 1 to simulate failure.";
        }
        return new CybrillaApiException(message);
    }

    private boolean syncLocalOrderForProviderPurchaseFailure(TransactionOrder order, String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        if (!lower.contains("failed") && !lower.contains("cancelled")) {
            return false;
        }
        order.setOrderStatus(OrderStatus.FAILED);
        order.setFailureReason(errorMessage);
        order.setInvestorActionUrl(null);
        return true;
    }

    private static String mfPurchaseFailureReason(JsonNode purchase) {
        if (purchase == null || purchase.isNull()) {
            return null;
        }
        for (String field : List.of("failure_reason", "failure_code", "remarks", "gateway_remarks", "reason")) {
            if (purchase.hasNonNull(field) && StringUtils.hasText(purchase.get(field).asText())) {
                return purchase.get(field).asText().trim();
            }
        }
        JsonNode error = purchase.path("error");
        if (error.hasNonNull("message") && StringUtils.hasText(error.get("message").asText())) {
            return error.get("message").asText().trim();
        }
        return null;
    }

    private static String providerFailureMessage(CybrillaApiException ex, String fallback) {
        String detail = ex.getMessage();
        if (!StringUtils.hasText(detail)) {
            return fallback;
        }
        if (detail.contains("review is still in progress") || detail.contains("submission is still in progress")) {
            return detail;
        }
        if (detail.contains("Unable to ") && detail.contains("Fintech Primitives")) {
            int statusIdx = detail.indexOf(": ");
            if (statusIdx > 0 && statusIdx + 2 < detail.length()) {
                String tail = detail.substring(statusIdx + 2);
                if (tail.length() <= 220) {
                    return "Payment could not be started: " + tail;
                }
            }
        }
        return fallback;
    }

    private void sleepReviewPollInterval() {
        try {
            Thread.sleep(MF_PURCHASE_REVIEW_POLL_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CybrillaApiException("Interrupted while waiting for purchase review to complete", ex);
        }
    }

    private int resolveFpBankAccountOldId(Investor investor) {
        InvestorBankAccount verified = resolveVerifiedBankAccount(investor);
        if (verified.getFpBankAccountOldId() != null && verified.getFpBankAccountOldId() > 0) {
            return verified.getFpBankAccountOldId();
        }
        JsonNode bankAccount = cybrillaClient.fetchBankAccount(verified.getCybrillaBankId());
        int oldId = bankAccount.path("old_id").asInt(0);
        if (oldId <= 0) {
            throw new CybrillaApiException("Fintech Primitives bank account response did not include old_id");
        }
        verified.setFpBankAccountOldId(oldId);
        bankAccountRepository.save(verified);
        return oldId;
    }

    private InvestorBankAccount resolveVerifiedBankAccount(Investor investor) {
        InvestorBankAccount verified = bankAccountRepository.findByInvestorId(investor.getId()).stream()
                .filter(account -> account.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                .findFirst()
                .orElseThrow(() -> new CybrillaApiException("Verified bank account is required before mandate creation"));
        if (!StringUtils.hasText(verified.getCybrillaBankId())) {
            investor = investorService.ensureMfInvestmentAccount(investor.getId());
            verified = bankAccountRepository.findByInvestorId(investor.getId()).stream()
                    .filter(account -> account.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                    .filter(account -> StringUtils.hasText(account.getCybrillaBankId()))
                    .findFirst()
                    .orElseThrow(() -> new CybrillaApiException(
                            "Verified bank account must be linked to Fintech Primitives (bac_*) before mandate creation"));
        }
        return verified;
    }

    private int mandateLimitFor(BigDecimal amount) {
        BigDecimal sipAmount = amount == null ? BigDecimal.valueOf(1000) : amount;
        BigDecimal limit = sipAmount.multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.CEILING);
        int minimum = 100_000;
        return Math.max(minimum, limit.intValue());
    }

    private String externalMandateType(String mandateMode) {
        if (mandateMode != null && "UPI".equalsIgnoreCase(mandateMode.trim())) {
            return "UPI";
        }
        return "E_MANDATE";
    }

    private boolean isSipMandateOrder(TransactionOrder order) {
        return order.getTransactionType() == TransactionType.SIP
                && order.getPaymentMode() != null
                && "MANDATE".equalsIgnoreCase(order.getPaymentMode().trim());
    }

    private String paymentPostbackUrlFor(String token) {
        if (!StringUtils.hasText(paymentPostbackUrl)) {
            return null;
        }
        String trimmed = paymentPostbackUrl.trim();
        if (trimmed.contains("{token}")) {
            return trimmed.replace("{token}", token);
        }
        if (trimmed.endsWith("/payment-complete")) {
            return trimmed.replace("/payment-complete", "/" + token + "/payment-complete");
        }
        return trimmed;
    }

    private Map<String, Object> consentPayload(Investor investor) {
        Map<String, Object> consent = new LinkedHashMap<>();
        if (StringUtils.hasText(investor.getEmail())) {
            consent.put("email", investor.getEmail().trim());
        }
        if (StringUtils.hasText(investor.getMobileNumber())) {
            consent.put("isd_code", "91");
            consent.put("mobile", investor.getMobileNumber().trim());
        }
        if (consent.isEmpty()) {
            throw new CybrillaApiException("Investor email or mobile is required before purchase consent can be recorded");
        }
        return consent;
    }

    private String paymentMethod(String paymentMode) {
        if (!StringUtils.hasText(paymentMode)) {
            return "NETBANKING";
        }
        return switch (paymentMode.trim().toUpperCase()) {
            case "BANK_TRANSFER" -> "UPI";
            case "UPI" -> "UPI";
            default -> "NETBANKING";
        };
    }

    private Integer parsePaymentId(String paymentId) {
        if (!StringUtils.hasText(paymentId)) {
            return null;
        }
        try {
            return Integer.parseInt(paymentId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String firstText(JsonNode node, String field, String fallback) {
        if (node != null && node.hasNonNull(field)) {
            return node.get(field).asText();
        }
        return fallback;
    }

    private TransactionOrder findOrder(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Investor action token is required");
        }
        return orderRepository.findByInvestorActionToken(token.trim())
                .orElseThrow(() -> new EntityNotFoundException("Investor action link not found"));
    }

    private InvestorActionPage pageFor(TransactionOrder order, String message) {
        Investor investor = investorRepository.findById(order.getInvestorId()).orElse(null);
        ProductScheme scheme = schemeRepository.findById(order.getProductSchemeId()).orElse(null);
        String paymentRedirectUrl = paymentRedirectUrl(order);
        String schemeName = ProductSchemeOrderSupport.displayName(order, scheme);
        String amcName = ProductSchemeOrderSupport.amcName(order, scheme);
        return new InvestorActionPage(
                order.getInvestorActionToken(),
                order.getId(),
                order.getExternalOrderId(),
                investor == null ? "Investor" : investor.getFullName(),
                investor == null ? null : investor.getEmail(),
                schemeName,
                amcName,
                order.getAmount(),
                order.getUnits(),
                order.getTransactionType() == null ? null : order.getTransactionType().name(),
                order.getOrderStatus(),
                order.getPaymentMode(),
                order.getMandateMode(),
                order.getExternalMandateId(),
                order.getMandateStatus(),
                order.getSipFrequency(),
                order.getSipStartDate(),
                order.getSipInstalments(),
                order.getOrderStatus() == OrderStatus.PENDING_INVESTOR_ACTION
                        || order.getOrderStatus() == OrderStatus.PROCESSING,
                message,
                paymentRedirectUrl,
                sandboxPaymentSimulationAllowed(order),
                sandboxMandateSimulationAllowed(order),
                buildDebugJson(order, investor, scheme, message, paymentRedirectUrl)
        );
    }

    private String buildDebugJson(
            TransactionOrder order,
            Investor investor,
            ProductScheme scheme,
            String message,
            String paymentRedirectUrl
    ) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("page", "investor-action");
        debug.put("timestamp", OffsetDateTime.now().toString());
        debug.put("cybrillaEnvironment", cybrillaEnvironment);
        debug.put("uiMessage", message);
        debug.put("localOrderId", order.getId() == null ? null : order.getId().toString());
        debug.put("localOrderStatus", order.getOrderStatus() == null ? null : order.getOrderStatus().name());
        debug.put("transactionType", order.getTransactionType() == null ? null : order.getTransactionType().name());
        debug.put("amount", order.getAmount());
        debug.put("externalOrderId", order.getExternalOrderId());
        debug.put("externalPaymentId", order.getExternalPaymentId());
        debug.put("failureReason", order.getFailureReason());
        debug.put("investorActionUrlStored", order.getInvestorActionUrl());
        debug.put("paymentRedirectResolved", paymentRedirectUrl);
        debug.put("schemeDisplayName", ProductSchemeOrderSupport.displayName(order, scheme));
        debug.put("schemeAmcName", ProductSchemeOrderSupport.amcName(order, scheme));
        debug.put("orderSchemeNameSnapshot", order.getProductSchemeName());
        debug.put("orderSchemeIsinSnapshot", order.getProductSchemeIsin());
        debug.put("orderSchemeCodeSnapshot", order.getProductSchemeExternalCode());
        debug.put("orderSchemeAmcSnapshot", order.getProductSchemeAmcName());
        debug.put("paymentPostbackTemplate", paymentPostbackUrl);
        debug.put("confirmationAllowed", order.getOrderStatus() == OrderStatus.PENDING_INVESTOR_ACTION
                || order.getOrderStatus() == OrderStatus.PROCESSING);
        debug.put("sandboxPaymentSimulateAllowed", sandboxPaymentSimulationAllowed(order));
        debug.put("sandboxMandateSimulateAllowed", sandboxMandateSimulationAllowed(order));
        debug.put(
                "expectedLumpsumFlow",
                List.of(
                        "POST /v2/mf_purchases → under_review → pending (FP review)",
                        "consent → POST /api/pg/payments/netbanking → PATCH confirm → submitted",
                        "UPI URI, hosted token_url, or sandbox simulate payment",
                        "postback → poll until successful"
                )
        );
        debug.put("sandboxAmountTip", "Amount must end in 0 for success (e.g. ₹5000); ending in 1 simulates failure.");
        if (order.getAmount() != null) {
            int lastDigit = order.getAmount().setScale(0, RoundingMode.DOWN).intValue() % 10;
            debug.put("amountLastDigit", lastDigit);
            debug.put("sandboxAmountLooksValid", lastDigit == 0);
        }
        if (investor != null) {
            debug.put("investorId", investor.getId() == null ? null : investor.getId().toString());
            debug.put("investorFpProfileId", investor.getCybrillaInvestorId());
            debug.put("investorMfiaId", investor.getExternalMfInvestmentAccountId());
        }
        if (scheme != null) {
            debug.put("schemeId", scheme.getId() == null ? null : scheme.getId().toString());
            debug.put("schemeIsin", scheme.getExternalIsin());
        }
        if (StringUtils.hasText(order.getExternalOrderId())) {
            try {
                JsonNode purchase = cybrillaClient.fetchMfPurchase(order.getExternalOrderId());
                Map<String, Object> fpPurchase = new LinkedHashMap<>();
                fpPurchase.put("state", purchase.path("state").asText(""));
                fpPurchase.put("oldId", purchase.path("old_id").asInt(0));
                fpPurchase.put("failureReason", mfPurchaseFailureReason(purchase));
                fpPurchase.put("failureCode", purchase.path("failure_code").asText(null));
                fpPurchase.put("gatewayRemarks", purchase.path("gateway_remarks").asText(null));
                fpPurchase.put("remarks", purchase.path("remarks").asText(null));
                debug.put("fpPurchase", fpPurchase);
            } catch (RuntimeException ex) {
                debug.put("fpPurchaseFetchError", ex.getMessage());
            }
        }
        if (order.getExternalPaymentId() != null && order.getExternalPaymentId() > 0) {
            try {
                JsonNode payment = cybrillaClient.fetchPayment(order.getExternalPaymentId());
                Map<String, Object> fpPayment = new LinkedHashMap<>();
                fpPayment.put("id", payment.path("id").asInt(0));
                fpPayment.put("status", payment.path("status").asText(""));
                fpPayment.put("hasTokenUrl", payment.hasNonNull("token_url") && StringUtils.hasText(payment.path("token_url").asText()));
                debug.put("fpPayment", fpPayment);
            } catch (RuntimeException ex) {
                debug.put("fpPaymentFetchError", ex.getMessage());
            }
        }
        try {
            return DEBUG_MAPPER.writeValueAsString(debug);
        } catch (JsonProcessingException ex) {
            logger.warn("investor_action_debug status='serialize_failed' order_id='{}' reason='{}'", order.getId(), ex.getMessage());
            return "{\"error\":\"debug serialization failed\"}";
        }
    }

    private boolean sandboxPaymentSimulationAllowed(TransactionOrder order) {
        if (!sandboxPaymentSimulationEnabled() || order.getOrderStatus() != OrderStatus.PAYMENT_PENDING) {
            return false;
        }
        if (isSipMandateOrder(order) && !MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())) {
            return false;
        }
        String actionUrl = order.getInvestorActionUrl();
        return !StringUtils.hasText(actionUrl)
                || isSandboxPaymentTokenUrl(actionUrl)
                || actionUrl.startsWith("http")
                || isUpiUri(actionUrl);
    }

    private boolean sandboxMandateSimulationAllowed(TransactionOrder order) {
        if (!sandboxMandateSimulationEnabled() || order.getOrderStatus() != OrderStatus.PAYMENT_PENDING) {
            return false;
        }
        if (!isSipMandateOrder(order) || MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())) {
            return false;
        }
        Integer mandateId = order.getExternalMandateId();
        return mandateId != null && mandateId > 0;
    }

    private String paymentRedirectUrl(TransactionOrder order) {
        if (order.getOrderStatus() != OrderStatus.PAYMENT_PENDING) {
            return null;
        }
        String actionUrl = order.getInvestorActionUrl();
        if (!StringUtils.hasText(actionUrl)) {
            return null;
        }
        if (actionUrl.startsWith("http")) {
            return actionUrl;
        }
        if (isUpiUri(actionUrl)) {
            return actionUrl;
        }
        if (sandboxPaymentSimulationEnabled() && isSandboxPaymentTokenUrl(actionUrl)) {
            return null;
        }
        return null;
    }

    private void finalizeLumpsumPurchase(TransactionOrder order) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            return;
        }
        String latestState = null;
        for (int attempt = 1; attempt <= PURCHASE_FINALIZE_POLL_ATTEMPTS; attempt++) {
            JsonNode purchase = cybrillaClient.fetchMfPurchase(order.getExternalOrderId());
            latestState = purchase.path("state").asText("");
            if ("successful".equalsIgnoreCase(latestState)) {
                order.setOrderStatus(OrderStatus.SUCCESSFUL);
                order.setInvestorActionUrl(null);
                return;
            }
            if ("failed".equalsIgnoreCase(latestState) || "cancelled".equalsIgnoreCase(latestState)) {
                order.setOrderStatus(OrderStatus.FAILED);
                order.setFailureReason("Purchase reached provider state '" + latestState + "'");
                return;
            }
            if (!"submitted".equalsIgnoreCase(latestState) && !"confirmed".equalsIgnoreCase(latestState)) {
                sleepReviewPollInterval();
            }
        }
        if (sandboxPaymentSimulationEnabled()
                && (latestState == null
                || "submitted".equalsIgnoreCase(latestState)
                || "confirmed".equalsIgnoreCase(latestState))) {
            order.setOrderStatus(OrderStatus.SUCCESSFUL);
            order.setInvestorActionUrl(null);
        }
    }

    private static boolean isSandboxPaymentTokenUrl(String tokenUrl) {
        String lower = tokenUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("sandbox://") || lower.contains("payments.mock");
    }

    private static boolean isUpiUri(String actionUrl) {
        return StringUtils.hasText(actionUrl) && actionUrl.toLowerCase(Locale.ROOT).startsWith("upi://");
    }

    private void requireSandboxPaymentSimulation() {
        if (!sandboxPaymentSimulationEnabled()) {
            throw new IllegalStateException(
                    "Payment simulation is only available in sandbox when CYBRILLA_SANDBOX_SIMULATE_PAYMENT is enabled.");
        }
    }

    private void requireSandboxMandateSimulation() {
        if (!sandboxMandateSimulationEnabled()) {
            throw new IllegalStateException(
                    "Mandate simulation is only available in sandbox when CYBRILLA_SANDBOX_SIMULATE_MANDATE_APPROVAL is enabled.");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private String messageFor(TransactionOrder order) {
        if (order.getOrderStatus() == OrderStatus.PAYMENT_PENDING && paymentRedirectUrl(order) != null) {
            if (isSipMandateOrder(order) && !MANDATE_STATUS_APPROVED.equalsIgnoreCase(order.getMandateStatus())) {
                return "Authorize your SIP mandate on the secure page, then return here to finish setup.";
            }
            if (isUpiUri(paymentRedirectUrl(order))) {
                return "UPI payment is ready. Open your UPI app to complete your purchase.";
            }
            return "Payment is ready. Continue to the secure payment page to complete your purchase.";
        }
        return switch (order.getOrderStatus()) {
            case PENDING_INVESTOR_ACTION -> isSipMandateOrder(order)
                    ? "Review the SIP details and confirm to register your mandate."
                    : "Review the details and confirm to start payment processing.";
            case PAYMENT_PENDING -> {
                if (sandboxMandateSimulationAllowed(order)) {
                    yield "Authorize your SIP mandate on the secure page, or simulate mandate approval below (sandbox).";
                }
                if (sandboxPaymentSimulationAllowed(order)) {
                    yield isUpiUri(paymentRedirectUrl(order))
                            ? "UPI payment is ready. Simulate payment below (sandbox) or open your UPI app."
                            : "Payment is ready. Simulate payment below (sandbox) or continue to the FP payment page.";
                }
                yield "Payment is pending provider confirmation.";
            }
            case SUBMITTED, PROCESSING -> {
                if (!isSipMandateOrder(order) && StringUtils.hasText(order.getFailureReason())) {
                    yield order.getFailureReason();
                }
                yield isSipMandateOrder(order)
                        ? "Your SIP mandate and first installment are being processed."
                        : "Your purchase is being processed.";
            }
            case SUCCESSFUL, COMPLETED -> isSipMandateOrder(order)
                    ? "This SIP is complete."
                    : "This purchase is complete.";
            case ACTIVE -> "This recurring purchase is active.";
            case FAILED -> {
                String reason = order.getFailureReason();
                if (StringUtils.hasText(reason) && reason.contains("investor_data_submission_error")) {
                    yield "Cybrilla ONDC purchase review rejected investor data submission. Local POA pre-verification and FP profile prep succeeded, "
                            + "so this is often a Cybrilla sandbox tenant configuration issue (FP bank verification returns \"Tenant platizio is not configured\"). "
                            + "Try Repair investor profile, then place a new order (Anita demo: PAN KRTPX3751K, bank ending 1193, amount ending in 0). "
                            + "If it persists, contact Cybrilla support with the external purchase id from Technical details below.";
                }
                yield StringUtils.hasText(reason)
                        ? "This purchase failed: " + reason
                        : "This purchase failed.";
            }
            case RETRY_AVAILABLE -> "This purchase needs another attempt. Please contact your distributor.";
            case CREATED, DRAFT -> "This purchase is not ready for investor action yet.";
            case PAUSED -> "This recurring purchase is paused.";
            case CANCELLED -> isSipMandateOrder(order)
                    ? "This SIP was cancelled with Fintech Primitives."
                    : "This purchase was cancelled.";
        };
    }

    public record InvestorActionPage(
            String token,
            UUID orderId,
            String externalOrderId,
            String investorName,
            String investorEmail,
            String schemeName,
            String amcName,
            BigDecimal amount,
            BigDecimal units,
            String transactionType,
            OrderStatus orderStatus,
            String paymentMode,
            String mandateMode,
            Integer externalMandateId,
            String mandateStatus,
            String sipFrequency,
            LocalDate sipStartDate,
            Integer sipInstalments,
            boolean confirmationAllowed,
            String message,
            String paymentRedirectUrl,
            boolean sandboxPaymentSimulationAllowed,
            boolean sandboxMandateSimulationAllowed,
            String debugJson
    ) {
    }
}

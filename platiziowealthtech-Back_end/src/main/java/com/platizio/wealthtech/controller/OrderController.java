package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderExecutionPlatform;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.BulkOrderUploadResponse;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.dto.SipCancelRequest;
import com.platizio.wealthtech.dto.SipUpdateRequest;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.BulkOrderUploadService;
import com.platizio.wealthtech.service.OrderService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final BulkOrderUploadService bulkOrderUploadService;

    public OrderController(OrderService orderService, BulkOrderUploadService bulkOrderUploadService) {
        this.orderService = orderService;
        this.bulkOrderUploadService = bulkOrderUploadService;
    }

    @PostMapping
    public TransactionOrder createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        return orderService.createOrder(request, authenticatedDistributorId(principal));
    }

    @PostMapping("/bulk")
    public List<TransactionOrder> createOrders(
            @Valid @RequestBody BulkOrderCreateRequest request,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) {
        return orderService.createOrders(request, authenticatedDistributorId(principal));
    }

    @PostMapping(value = "/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkOrderUploadResponse uploadBulkOrders(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "AUTO") BulkOrderExecutionPlatform platform,
            @RequestParam(defaultValue = "25") int batchSize,
            @AuthenticationPrincipal AuthenticatedDistributorPrincipal principal
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("CSV file is required");
        }
        try (InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return bulkOrderUploadService.process(
                    reader,
                    authenticatedDistributorId(principal),
                    dryRun,
                    platform,
                    batchSize
            );
        }
    }

    @GetMapping
    public Page<TransactionOrder> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction,
            Authentication auth
    ) {
        JwtAuthPrincipal principal = actorPrincipal(auth);
        return orderService.listOrders(principal.getDistributorId(), principal.getRole(), status, from, to, page, size, sortBy, direction);
    }

    @GetMapping("/{orderId}")
    public TransactionOrder getOrder(@PathVariable UUID orderId, Authentication auth) {
        return orderService.getOrder(orderId, actorPrincipal(auth));
    }

    @PostMapping("/{orderId}/sync-provider-status")
    public TransactionOrder syncProviderStatus(@PathVariable UUID orderId, Authentication auth) {
        return orderService.syncLumpsumOrderFromProvider(orderId, actorPrincipal(auth));
    }

    @GetMapping("/by-investor/{investorId}")
    public List<TransactionOrder> listByInvestor(@PathVariable UUID investorId, Authentication auth) {
        return orderService.listOrdersByInvestor(investorId, actorPrincipal(auth));
    }

    @GetMapping("/by-distributor/{distributorId}")
    public List<TransactionOrder> listByDistributor(@PathVariable UUID distributorId, Authentication auth) {
        return orderService.listOrdersByDistributor(distributorId, actorPrincipal(auth));
    }

    @GetMapping("/{orderId}/redemptions")
    public List<RedemptionRecord> listRedemptions(@PathVariable UUID orderId, Authentication auth) {
        return orderService.listRedemptionsByOrder(orderId, actorPrincipal(auth));
    }

    /** Reconciles redemption records for an order against authoritative Fintech Primitives state. */
    @PostMapping("/{orderId}/redemptions/sync")
    public List<RedemptionRecord> syncRedemptions(@PathVariable UUID orderId, Authentication auth) {
        return orderService.syncRedemptionsForOrder(orderId, actorPrincipal(auth));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{orderId}/status")
    public TransactionOrder updateStatus(
            @PathVariable UUID orderId,
            @RequestParam OrderStatus status,
            @RequestParam(required = false) String failureReason,
            Authentication auth
    ) {
        return orderService.updateOrderStatus(orderId, status, failureReason, actorId(auth));
    }

    @PostMapping("/{orderId}/redemption")
    public RedemptionRecord createRedemption(@PathVariable UUID orderId, Authentication auth) {
        return orderService.createRedemption(orderId, actorPrincipal(auth));
    }

    /**
     * Distributor asks the investor to approve this purchase/SIP order with 2FA
     * (Phase-2). Creates the approval challenge and flips the order to
     * PENDING_INVESTOR_ACTION. The response is a challenge summary — NEVER an OTP.
     */
    @PostMapping("/{orderId}/request-investor-approval")
    public OrderService.ApprovalRequestResult requestInvestorApproval(
            @PathVariable UUID orderId, Authentication auth) {
        return orderService.requestInvestorApproval(orderId, actorPrincipal(auth));
    }

    /**
     * Distributor resends the approval link/code for an existing challenge (Phase-2).
     * The response hides the live OTP code (DF-13).
     */
    @PostMapping("/{orderId}/resend-approval-link")
    public OtpRequestResponse resendApprovalLink(
            @PathVariable UUID orderId,
            @RequestParam UUID challengeId,
            Authentication auth) {
        return orderService.resendApprovalLink(orderId, challengeId, actorPrincipal(auth));
    }

    @PostMapping("/{orderId}/cancel")
    public TransactionOrder cancelSipOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) SipCancelRequest request,
            Authentication auth
    ) {
        return orderService.cancelSipOrder(orderId, actorId(auth), request);
    }

    @PatchMapping("/{orderId}/sip")
    public TransactionOrder updateSip(
            @PathVariable UUID orderId,
            @Valid @RequestBody SipUpdateRequest request,
            Authentication auth
    ) {
        return orderService.updateSipPlan(orderId, request, actorId(auth));
    }

    /**
     * Lumpsum orders are soft-deleted. SIP DELETE is treated as cancel for backward-compatible clients
     * that still call DELETE instead of {@code POST /{orderId}/cancel}.
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<?> deleteOrder(@PathVariable UUID orderId, Authentication auth) {
        UUID actor = actorId(auth);
        TransactionOrder order = orderService.getOrder(orderId);
        if (order.getTransactionType() == TransactionType.SIP) {
            TransactionOrder cancelled = orderService.cancelSipOrder(orderId, actor, null);
            return ResponseEntity.ok(cancelled);
        }
        orderService.deleteOrder(orderId, actor);
        return ResponseEntity.noContent().build();
    }

    private UUID authenticatedDistributorId(AuthenticatedDistributorPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return principal.distributorId();
    }

    private UUID actorId(Authentication auth) {
        return actorPrincipal(auth).getDistributorId();
    }

    private JwtAuthPrincipal actorPrincipal(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return (JwtAuthPrincipal) auth.getPrincipal();
    }
}

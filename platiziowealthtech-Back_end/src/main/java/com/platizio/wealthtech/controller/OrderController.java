package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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

    @GetMapping("/{orderId}")
    public TransactionOrder getOrder(@PathVariable UUID orderId) {
        return orderService.getOrder(orderId);
    }

    @GetMapping("/by-investor/{investorId}")
    public List<TransactionOrder> listByInvestor(@PathVariable UUID investorId) {
        return orderService.listOrdersByInvestor(investorId);
    }

    @GetMapping("/by-distributor/{distributorId}")
    public List<TransactionOrder> listByDistributor(@PathVariable UUID distributorId) {
        return orderService.listOrdersByDistributor(distributorId);
    }

    @GetMapping("/{orderId}/redemptions")
    public List<RedemptionRecord> listRedemptions(@PathVariable UUID orderId) {
        return orderService.listRedemptionsByOrder(orderId);
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
        return orderService.createRedemption(orderId, actorId(auth));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{orderId}")
    public void deleteOrder(@PathVariable UUID orderId, Authentication auth) {
        orderService.deleteOrder(orderId, actorId(auth));
    }

    private UUID authenticatedDistributorId(AuthenticatedDistributorPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return principal.distributorId();
    }

    private UUID actorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}

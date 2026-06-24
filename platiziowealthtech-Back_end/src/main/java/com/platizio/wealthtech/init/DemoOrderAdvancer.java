package com.platizio.wealthtech.init;

import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.service.OrderService;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo-only order lifecycle simulator.
 *
 * <p>The real production flow advances an order from PAYMENT_PENDING through
 * SUBMITTED → PROCESSING → SUCCESSFUL via a provider webhook (Cybrilla),
 * which doesn't exist yet (see MVP-B9). Without it, every demo transaction
 * dead-ends at PAYMENT_PENDING and the user never sees the happy-path
 * "Order Completed" notification, so the most critical flow looks broken.
 *
 * <p>This bean runs on the {@code local} or {@code demo} profile AND only when
 * {@code app.demo.order-advancer-enabled=true} (DF-12). The local profile turns
 * it on by default; the demo profile turns it on so the happy path completes on
 * screen, but it can be switched off ({@code DEMO_ORDER_ADVANCER_ENABLED=false})
 * to walk the real payment flow manually. It polls every 5 seconds and advances
 * any order whose updatedAt is older than {@link #ADVANCE_AGE_SECONDS} seconds:
 *
 * <pre>
 *   PAYMENT_PENDING → SUBMITTED  → PROCESSING → SUCCESSFUL
 *       (5s)            (5s)        (5s)
 * </pre>
 *
 * <p>End-to-end a demo order takes ~15 seconds to "complete" after the
 * investor confirms, which feels like real provider processing latency.
 * OrderService.updateOrderStatus is called for each step so the existing
 * audit-log + distributor-notification flow fires exactly as it would
 * in production.
 *
 * <p>For prod (any non-local profile), this bean is not instantiated; the
 * admin endpoint {@code PATCH /api/v1/orders/{orderId}/status?status=…} is
 * the documented manual fallback while the real webhook is on the roadmap.
 */
@Component
@Profile({"local", "demo"})
@ConditionalOnProperty(name = "app.demo.order-advancer-enabled", havingValue = "true", matchIfMissing = false)
public class DemoOrderAdvancer {

    private static final Logger logger = LoggerFactory.getLogger(DemoOrderAdvancer.class);

    /** Minimum seconds an order must sit in a transient status before we bump it. */
    private static final long ADVANCE_AGE_SECONDS = 5;

    /** Next-state map for the demo state machine. Terminal states aren't keyed. */
    private static final Map<OrderStatus, OrderStatus> NEXT_STATE;
    static {
        NEXT_STATE = new EnumMap<>(OrderStatus.class);
        NEXT_STATE.put(OrderStatus.PAYMENT_PENDING, OrderStatus.SUBMITTED);
        NEXT_STATE.put(OrderStatus.SUBMITTED, OrderStatus.PROCESSING);
        NEXT_STATE.put(OrderStatus.PROCESSING, OrderStatus.SUCCESSFUL);
    }

    /** Statuses we actively poll for advancement. */
    private static final Set<OrderStatus> TRANSIENT_STATUSES = NEXT_STATE.keySet();

    private final TransactionOrderRepository orderRepository;
    private final OrderService orderService;

    public DemoOrderAdvancer(TransactionOrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "5000", initialDelayString = "10000")
    public void advanceStuckOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(ADVANCE_AGE_SECONDS);
        List<TransactionOrder> stuck = orderRepository
                .findByOrderStatusInAndUpdatedAtBefore(TRANSIENT_STATUSES, cutoff);

        if (stuck.isEmpty()) {
            return;
        }

        for (TransactionOrder order : stuck) {
            OrderStatus current = order.getOrderStatus();
            OrderStatus next = NEXT_STATE.get(current);
            if (next == null) {
                continue; // shouldn't happen given the query, but defensive
            }
            try {
                // actorId = the order's own distributor so the audit log
                // attributes the simulated update to a real principal rather
                // than a NIL UUID.
                orderService.updateOrderStatus(order.getId(), next, null, order.getDistributorId());
                logger.info(
                        "demo_order_advance status='advanced' orderId='{}' from='{}' to='{}'",
                        order.getId(), current, next);
            } catch (RuntimeException ex) {
                logger.warn(
                        "demo_order_advance status='failed' orderId='{}' from='{}' to='{}' reason='{}'",
                        order.getId(), current, next, ex.getMessage());
            }
        }
    }
}

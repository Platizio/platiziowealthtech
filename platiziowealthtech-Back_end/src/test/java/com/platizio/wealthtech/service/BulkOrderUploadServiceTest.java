package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderExecutionPlatform;
import com.platizio.wealthtech.dto.BulkOrderUploadResponse;
import com.platizio.wealthtech.dto.BulkOrderUploadRowStatus;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BulkOrderUploadServiceTest {

    @Test
    void dryRunValidatesRowsAgainstSchemeMasterWithoutSubmitting() {
        UUID investorId = UUID.randomUUID();
        ProductScheme scheme = scheme(UUID.randomUUID(), "MF-201", true);
        CapturingOrderService orderService = new CapturingOrderService(submittedOrder(UUID.randomUUID(), investorId, scheme.getId()));
        BulkOrderUploadService service = new BulkOrderUploadService(repository(scheme), orderService);

        BulkOrderUploadResponse response = service.process(
                new StringReader("""
                        investorId,schemeCode,transactionType,amount,paymentMode
                        %s,MF-201,LUMPSUM_PURCHASE,2500,NET_BANKING
                        """.formatted(investorId)),
                UUID.randomUUID(),
                true,
                BulkOrderExecutionPlatform.BSE_STAR_MF,
                10
        );

        assertThat(response.totalRows()).isEqualTo(1);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.submittedRows()).isZero();
        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(BulkOrderUploadRowStatus.VALIDATED);
            assertThat(row.productSchemeId()).isEqualTo(scheme.getId());
            assertThat(row.schemeCode()).isEqualTo("MF-201");
        });
        assertThat(orderService.calls).isZero();
    }

    @Test
    void submitProcessesValidRowsAndReturnsSubmittedOrderDetails() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        ProductScheme scheme = scheme(UUID.randomUUID(), "MF-201", true);
        TransactionOrder submitted = submittedOrder(UUID.randomUUID(), investorId, scheme.getId());
        CapturingOrderService orderService = new CapturingOrderService(submitted);
        BulkOrderUploadService service = new BulkOrderUploadService(repository(scheme), orderService);

        BulkOrderUploadResponse response = service.process(
                new StringReader("""
                        investorId,schemeCode,transactionType,amount,paymentMode
                        %s,MF-201,PURCHASE,2500,NET_BANKING
                        """.formatted(investorId)),
                distributorId,
                false,
                BulkOrderExecutionPlatform.NSE_NMF_II,
                10
        );

        assertThat(orderService.calls).isEqualTo(1);
        assertThat(orderService.lastDistributorId).isEqualTo(distributorId);
        assertThat(orderService.lastRequest.productSchemeId()).isEqualTo(scheme.getId());
        assertThat(orderService.lastRequest.transactionType()).isEqualTo(TransactionType.LUMPSUM_PURCHASE);
        assertThat(response.submittedRows()).isEqualTo(1);
        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(BulkOrderUploadRowStatus.SUBMITTED);
            assertThat(row.orderId()).isEqualTo(submitted.getId());
            assertThat(row.externalOrderId()).isEqualTo("external-1");
        });
    }

    @Test
    void invalidSchemeDoesNotSubmitRow() {
        UUID investorId = UUID.randomUUID();
        CapturingOrderService orderService = new CapturingOrderService(null);
        BulkOrderUploadService service = new BulkOrderUploadService(repository(), orderService);

        BulkOrderUploadResponse response = service.process(
                new StringReader("""
                        investorId,schemeCode,transactionType,amount
                        %s,BAD,LUMPSUM_PURCHASE,2500
                        """.formatted(investorId)),
                UUID.randomUUID(),
                false,
                BulkOrderExecutionPlatform.AUTO,
                10
        );

        assertThat(response.failedRows()).isEqualTo(1);
        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(BulkOrderUploadRowStatus.VALIDATION_FAILED);
            assertThat(row.errors()).contains("Scheme was not found in the scheme master");
        });
        assertThat(orderService.calls).isZero();
    }

    private ProductSchemeRepository repository(ProductScheme... schemes) {
        return (ProductSchemeRepository) Proxy.newProxyInstance(
                ProductSchemeRepository.class.getClassLoader(),
                new Class<?>[]{ProductSchemeRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> findById((UUID) args[0], schemes);
                    case "findFirstByExternalSchemeCodeIgnoreCase" -> findByCode((String) args[0], schemes);
                    case "findFirstByExternalIsinIgnoreCase" -> Optional.empty();
                    case "findFirstBySchemeNameIgnoreCase" -> findByName((String) args[0], schemes);
                    case "findAll" -> List.of(schemes);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Optional<ProductScheme> findById(UUID id, ProductScheme[] schemes) {
        for (ProductScheme scheme : schemes) {
            if (scheme.getId().equals(id)) {
                return Optional.of(scheme);
            }
        }
        return Optional.empty();
    }

    private Optional<ProductScheme> findByCode(String code, ProductScheme[] schemes) {
        for (ProductScheme scheme : schemes) {
            if (scheme.getExternalSchemeCode().equalsIgnoreCase(code)) {
                return Optional.of(scheme);
            }
        }
        return Optional.empty();
    }

    private Optional<ProductScheme> findByName(String name, ProductScheme[] schemes) {
        for (ProductScheme scheme : schemes) {
            if (scheme.getSchemeName().equalsIgnoreCase(name)) {
                return Optional.of(scheme);
            }
        }
        return Optional.empty();
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

    private ProductScheme scheme(UUID id, String code, boolean active) {
        ProductScheme scheme = new ProductScheme();
        ReflectionTestUtils.setField(scheme, "id", id);
        scheme.setSchemeName("Balanced Mutual Fund");
        scheme.setAmcName("Platizio Assets");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode(code);
        scheme.setActive(active);
        return scheme;
    }

    private TransactionOrder submittedOrder(UUID orderId, UUID investorId, UUID schemeId) {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", orderId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(schemeId);
        order.setTransactionType(TransactionType.LUMPSUM_PURCHASE);
        order.setAmount(BigDecimal.valueOf(2500));
        order.setExternalOrderId("external-1");
        order.setInvestorActionUrl("https://example.test/action");
        return order;
    }

    private static class CapturingOrderService extends OrderService {

        private final TransactionOrder order;
        private int calls;
        private UUID lastDistributorId;
        private OrderCreateRequest lastRequest;

        CapturingOrderService(TransactionOrder order) {
            super(null, null, null, null, null, null, null, null);
            this.order = order;
        }

        @Override
        public TransactionOrder createOrder(OrderCreateRequest request, UUID distributorId) {
            calls++;
            lastRequest = request;
            lastDistributorId = distributorId;
            return order;
        }
    }
}

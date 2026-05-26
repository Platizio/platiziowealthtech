package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderExecutionPlatform;
import com.platizio.wealthtech.dto.BulkOrderUploadResponse;
import com.platizio.wealthtech.dto.BulkOrderUploadRowResult;
import com.platizio.wealthtech.dto.BulkOrderUploadRowStatus;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BulkOrderUploadService {

    private static final int DEFAULT_BATCH_SIZE = 25;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_ROWS = 1_000;

    private final ProductSchemeRepository productSchemeRepository;
    private final OrderService orderService;

    public BulkOrderUploadService(ProductSchemeRepository productSchemeRepository, OrderService orderService) {
        this.productSchemeRepository = productSchemeRepository;
        this.orderService = orderService;
    }

    public BulkOrderUploadResponse process(
            Reader csvReader,
            UUID distributorId,
            boolean dryRun,
            BulkOrderExecutionPlatform platform,
            Integer requestedBatchSize
    ) {
        int batchSize = normalizeBatchSize(requestedBatchSize);
        List<CsvRow> csvRows = parseCsv(csvReader);
        if (csvRows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Bulk order upload supports up to " + MAX_ROWS + " rows per file");
        }

        Map<Integer, BulkOrderUploadRowResult> results = new LinkedHashMap<>();
        List<RowWork> validRows = new ArrayList<>();

        for (CsvRow csvRow : csvRows) {
            RowWork rowWork = validateRow(csvRow);
            results.put(csvRow.rowNumber(), rowWork.result());
            if (rowWork.request() != null) {
                validRows.add(rowWork);
            }
        }

        int submittedRows = 0;
        if (!dryRun) {
            for (int start = 0; start < validRows.size(); start += batchSize) {
                int end = Math.min(start + batchSize, validRows.size());
                for (RowWork rowWork : validRows.subList(start, end)) {
                    BulkOrderUploadRowResult updated = submitRow(rowWork, distributorId);
                    if (updated.status() == BulkOrderUploadRowStatus.SUBMITTED) {
                        submittedRows++;
                    }
                    results.put(updated.rowNumber(), updated);
                }
            }
        }

        int failedRows = (int) results.values().stream()
                .filter(result -> result.status() == BulkOrderUploadRowStatus.VALIDATION_FAILED
                        || result.status() == BulkOrderUploadRowStatus.SUBMISSION_FAILED)
                .count();

        return new BulkOrderUploadResponse(
                csvRows.size(),
                validRows.size(),
                submittedRows,
                failedRows,
                batchSize,
                platform == null ? BulkOrderExecutionPlatform.AUTO : platform,
                dryRun,
                List.copyOf(results.values())
        );
    }

    private RowWork validateRow(CsvRow csvRow) {
        List<String> errors = new ArrayList<>();

        UUID investorId = parseUuid(value(csvRow.values(), "investorId", "investor_id", "investor"), "investorId", errors);
        TransactionType transactionType = parseTransactionType(value(csvRow.values(), "transactionType", "transaction_type", "type"), errors);
        ProductScheme scheme = resolveScheme(csvRow.values(), errors);
        BigDecimal amount = parseBigDecimal(value(csvRow.values(), "amount"), "amount", errors);
        BigDecimal units = parseBigDecimal(value(csvRow.values(), "units"), "units", errors);
        String paymentMode = defaultText(value(csvRow.values(), "paymentMode", "payment_mode"), "NET_BANKING");
        String mandateMode = value(csvRow.values(), "mandateMode", "mandate_mode");
        String sipFrequency = value(csvRow.values(), "sipFrequency", "sip_frequency", "frequency");
        LocalDate sipStartDate = parseDate(value(csvRow.values(), "sipStartDate", "sip_start_date"), "sipStartDate", errors);
        Integer sipInstalments = parseInteger(value(csvRow.values(), "sipInstalments", "sip_instalments"), "sipInstalments", errors);

        if (amount == null && units == null) {
            errors.add("Either amount or units is required");
        }
        if (transactionType == TransactionType.SIP) {
            if (amount == null) {
                errors.add("SIP rows require amount");
            } else if (amount.compareTo(BigDecimal.valueOf(500)) < 0) {
                errors.add("SIP amount must be at least 500");
            }
            if (!StringUtils.hasText(sipFrequency)) {
                errors.add("SIP rows require sipFrequency");
            } else {
                String normalizedFrequency = sipFrequency.trim().toUpperCase(Locale.ROOT);
                if (!"MONTHLY".equals(normalizedFrequency) && !"QUARTERLY".equals(normalizedFrequency)) {
                    errors.add("SIP frequency must be MONTHLY or QUARTERLY");
                }
            }
            if (sipStartDate == null) {
                errors.add("SIP rows require sipStartDate");
            } else if (!sipStartDate.isAfter(LocalDate.now())) {
                errors.add("SIP start date must be in the future");
            }
        }

        BulkOrderUploadRowResult baseResult = result(
                csvRow.rowNumber(),
                investorId,
                scheme,
                transactionType,
                amount,
                units,
                errors.isEmpty() ? BulkOrderUploadRowStatus.VALIDATED : BulkOrderUploadRowStatus.VALIDATION_FAILED,
                null,
                null,
                null,
                errors
        );

        if (!errors.isEmpty()) {
            return new RowWork(csvRow.rowNumber(), null, baseResult);
        }

        OrderCreateRequest request = new OrderCreateRequest(
                investorId,
                scheme.getId(),
                null,
                transactionType,
                amount,
                units,
                paymentMode,
                mandateMode,
                sipFrequency,
                sipStartDate,
                sipInstalments
        );
        return new RowWork(csvRow.rowNumber(), request, baseResult);
    }

    private BulkOrderUploadRowResult submitRow(RowWork rowWork, UUID distributorId) {
        try {
            TransactionOrder order = orderService.createOrder(rowWork.request(), distributorId);
            BulkOrderUploadRowResult previous = rowWork.result();
            return new BulkOrderUploadRowResult(
                    previous.rowNumber(),
                    order.getInvestorId(),
                    order.getProductSchemeId(),
                    previous.schemeCode(),
                    previous.schemeName(),
                    order.getTransactionType(),
                    order.getAmount(),
                    order.getUnits(),
                    BulkOrderUploadRowStatus.SUBMITTED,
                    order.getId(),
                    order.getExternalOrderId(),
                    order.getInvestorActionUrl(),
                    List.of()
            );
        } catch (RuntimeException ex) {
            BulkOrderUploadRowResult previous = rowWork.result();
            return new BulkOrderUploadRowResult(
                    previous.rowNumber(),
                    previous.investorId(),
                    previous.productSchemeId(),
                    previous.schemeCode(),
                    previous.schemeName(),
                    previous.transactionType(),
                    previous.amount(),
                    previous.units(),
                    BulkOrderUploadRowStatus.SUBMISSION_FAILED,
                    null,
                    null,
                    null,
                    List.of(ex.getMessage() == null ? "Order submission failed" : ex.getMessage())
            );
        }
    }

    private ProductScheme resolveScheme(Map<String, String> row, List<String> errors) {
        String schemeId = value(row, "productSchemeId", "product_scheme_id", "schemeId", "scheme_id");
        if (StringUtils.hasText(schemeId)) {
            UUID id = parseUuid(schemeId, "productSchemeId", errors);
            if (id != null) {
                return validateScheme(productSchemeRepository.findById(id), errors);
            }
            return null;
        }

        String schemeCode = value(row, "schemeCode", "scheme_code", "externalSchemeCode", "external_scheme_code");
        if (StringUtils.hasText(schemeCode)) {
            return validateScheme(productSchemeRepository.findFirstByExternalSchemeCodeIgnoreCase(schemeCode.trim()), errors);
        }

        String isin = value(row, "isin", "externalIsin", "external_isin");
        if (StringUtils.hasText(isin)) {
            return validateScheme(productSchemeRepository.findFirstByExternalIsinIgnoreCase(isin.trim()), errors);
        }

        String schemeName = value(row, "schemeName", "scheme_name");
        if (StringUtils.hasText(schemeName)) {
            return validateScheme(productSchemeRepository.findFirstBySchemeNameIgnoreCase(schemeName.trim()), errors);
        }

        errors.add("One of productSchemeId, schemeCode, isin, or schemeName is required");
        return null;
    }

    private ProductScheme validateScheme(Optional<ProductScheme> schemeOptional, List<String> errors) {
        if (schemeOptional.isEmpty()) {
            errors.add("Scheme was not found in the scheme master");
            return null;
        }
        ProductScheme scheme = schemeOptional.get();
        if (Boolean.FALSE.equals(scheme.getActive())) {
            errors.add("Scheme is inactive in the scheme master");
        }
        return scheme;
    }

    private BulkOrderUploadRowResult result(
            int rowNumber,
            UUID investorId,
            ProductScheme scheme,
            TransactionType transactionType,
            BigDecimal amount,
            BigDecimal units,
            BulkOrderUploadRowStatus status,
            UUID orderId,
            String externalOrderId,
            String investorActionUrl,
            List<String> errors
    ) {
        return new BulkOrderUploadRowResult(
                rowNumber,
                investorId,
                scheme == null ? null : scheme.getId(),
                scheme == null ? null : scheme.getExternalSchemeCode(),
                scheme == null ? null : scheme.getSchemeName(),
                transactionType,
                amount,
                units,
                status,
                orderId,
                externalOrderId,
                investorActionUrl,
                List.copyOf(errors)
        );
    }

    private BulkOrderUploadRowResult result(
            int rowNumber,
            UUID investorId,
            UUID productSchemeId,
            TransactionType transactionType,
            BigDecimal amount,
            BigDecimal units,
            BulkOrderUploadRowStatus status,
            UUID orderId,
            String externalOrderId,
            String investorActionUrl,
            List<String> errors
    ) {
        return new BulkOrderUploadRowResult(
                rowNumber,
                investorId,
                productSchemeId,
                null,
                null,
                transactionType,
                amount,
                units,
                status,
                orderId,
                externalOrderId,
                investorActionUrl,
                List.copyOf(errors)
        );
    }

    private TransactionType parseTransactionType(String raw, List<String> errors) {
        if (!StringUtils.hasText(raw)) {
            errors.add("transactionType is required");
            return null;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        normalized = switch (normalized) {
            case "PURCHASE", "LUMPSUM", "LUMPSUM_PURCHASE" -> "LUMPSUM_PURCHASE";
            case "REDEEM", "REDEMPTION" -> "REDEMPTION";
            default -> normalized;
        };

        try {
            return TransactionType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            errors.add("transactionType is invalid: " + raw);
            return null;
        }
    }

    private UUID parseUuid(String raw, String field, List<String> errors) {
        if (!StringUtils.hasText(raw)) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            errors.add(field + " must be a valid UUID");
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String raw, String field, List<String> errors) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(",", ""));
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(field + " must be greater than zero");
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            errors.add(field + " must be numeric");
            return null;
        }
    }

    private LocalDate parseDate(String raw, String field, List<String> errors) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException ex) {
            errors.add(field + " must be in YYYY-MM-DD format");
            return null;
        }
    }

    private Integer parseInteger(String raw, String field, List<String> errors) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                errors.add(field + " must be greater than zero");
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            errors.add(field + " must be a whole number");
            return null;
        }
    }

    private String value(Map<String, String> row, String... aliases) {
        for (String alias : aliases) {
            String value = row.get(normalizeHeader(alias));
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String defaultText(String raw, String defaultValue) {
        return StringUtils.hasText(raw) ? raw.trim() : defaultValue;
    }

    private int normalizeBatchSize(Integer requestedBatchSize) {
        if (requestedBatchSize == null) {
            return DEFAULT_BATCH_SIZE;
        }
        return Math.min(Math.max(requestedBatchSize, 1), MAX_BATCH_SIZE);
    }

    private List<CsvRow> parseCsv(Reader reader) {
        List<List<String>> records;
        try {
            records = parseRecords(reader);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read CSV file", ex);
        }

        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty");
        }

        List<String> headers = records.get(0);
        if (!headers.isEmpty()) {
            headers.set(0, headers.get(0).replace("\uFEFF", ""));
        }

        List<CsvRow> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(cell -> !StringUtils.hasText(cell))) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                String header = headers.get(j);
                if (!StringUtils.hasText(header)) {
                    continue;
                }
                values.put(normalizeHeader(header), j < cells.size() ? cells.get(j) : "");
            }
            rows.add(new CsvRow(i + 1, values));
        }
        return rows;
    }

    private List<List<String>> parseRecords(Reader reader) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            int ch;
            while ((ch = bufferedReader.read()) != -1) {
                content.append((char) ch);
            }
        }

        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (ch == ',' && !inQuotes) {
                row.add(cell.toString().trim());
                cell.setLength(0);
                continue;
            }
            if ((ch == '\n' || ch == '\r') && !inQuotes) {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                records.add(row);
                row = new ArrayList<>();
                continue;
            }
            cell.append(ch);
        }

        if (!row.isEmpty() || cell.length() > 0) {
            row.add(cell.toString().trim());
            records.add(row);
        }
        return records;
    }

    private String normalizeHeader(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record CsvRow(int rowNumber, Map<String, String> values) {
    }

    private record RowWork(int rowNumber, OrderCreateRequest request, BulkOrderUploadRowResult result) {
    }
}

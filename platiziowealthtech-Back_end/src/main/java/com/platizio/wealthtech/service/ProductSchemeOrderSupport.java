package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import org.springframework.util.StringUtils;

final class ProductSchemeOrderSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProductSchemeOrderSupport() {
    }

    /**
     * POA-orderable when the scheme has an ISIN and is not an OMS {@code fund_schemes} import.
     * Live {@code mf_scheme_plans/cybrillapoa} rows qualify via ISIN; metadata may only contain NAV/returns.
     */
    static boolean isPoaOrderable(ProductScheme scheme) {
        if (scheme == null || !Boolean.TRUE.equals(scheme.getActive())) {
            return false;
        }
        if (!StringUtils.hasText(scheme.getExternalIsin())) {
            return false;
        }
        if (isOmsFundScheme(scheme)) {
            return false;
        }

        JsonNode metadata = parseMetadata(scheme.getMetadataJson());
        if (metadata != null && !metadata.isNull()) {
            String gateway = text(metadata, "gateway");
            if ("cybrillapoa".equalsIgnoreCase(gateway)) {
                return true;
            }
            String objectType = text(metadata, "object");
            if (StringUtils.hasText(objectType)) {
                String normalized = objectType.trim().toLowerCase();
                if ("mf_scheme_plan".equals(normalized) || "sif_scheme_plan".equals(normalized)) {
                    return true;
                }
            }
        }

        String fetchJson = scheme.getExternalFetchRequestJson();
        if (StringUtils.hasText(fetchJson)) {
            String normalizedFetch = fetchJson.toLowerCase();
            if (normalizedFetch.contains("mf_scheme_plans") || normalizedFetch.contains("sif_scheme_plans")) {
                return true;
            }
        }

        return true;
    }

    static void requirePoaOrderable(ProductScheme scheme) {
        if (scheme == null) {
            throw new IllegalArgumentException("Product scheme is required");
        }
        if (isOmsFundScheme(scheme)) {
            throw new IllegalArgumentException(
                    "Selected fund came from OMS fund_schemes and cannot be ordered via Cybrilla POA. "
                            + "Choose a scheme from GET /v2/mf_scheme_plans/cybrillapoa.");
        }
        if (!StringUtils.hasText(scheme.getExternalIsin())) {
            throw new IllegalArgumentException(
                    "Selected fund is missing an ISIN required for Cybrilla POA orders. Re-sync the POA catalogue and retry.");
        }
        if (!Boolean.TRUE.equals(scheme.getActive())) {
            throw new IllegalArgumentException("Selected fund is inactive.");
        }
    }

    static String displayName(ProductScheme scheme) {
        if (scheme == null) {
            return "Unknown fund";
        }
        if (StringUtils.hasText(scheme.getSchemeName())) {
            return scheme.getSchemeName().trim();
        }
        if (StringUtils.hasText(scheme.getExternalIsin())) {
            return scheme.getExternalIsin().trim();
        }
        if (StringUtils.hasText(scheme.getExternalSchemeCode())) {
            return scheme.getExternalSchemeCode().trim();
        }
        return "Unknown fund";
    }

    static String displayName(TransactionOrder order, ProductScheme scheme) {
        String schemeName = displayName(scheme);
        if (!"Unknown fund".equals(schemeName)) {
            return schemeName;
        }
        if (order == null) {
            return "Unknown fund";
        }
        if (StringUtils.hasText(order.getProductSchemeName())) {
            return order.getProductSchemeName().trim();
        }
        if (StringUtils.hasText(order.getProductSchemeIsin())) {
            return order.getProductSchemeIsin().trim();
        }
        if (StringUtils.hasText(order.getProductSchemeExternalCode())) {
            return order.getProductSchemeExternalCode().trim();
        }
        return "Unknown fund";
    }

    static String amcName(TransactionOrder order, ProductScheme scheme) {
        if (scheme != null && StringUtils.hasText(scheme.getAmcName())) {
            return scheme.getAmcName().trim();
        }
        if (order != null && StringUtils.hasText(order.getProductSchemeAmcName())) {
            return order.getProductSchemeAmcName().trim();
        }
        return "-";
    }

    private static boolean isOmsFundScheme(ProductScheme scheme) {
        String fetchJson = scheme == null ? null : scheme.getExternalFetchRequestJson();
        return StringUtils.hasText(fetchJson)
                && fetchJson.toLowerCase().contains("/api/oms/fund_schemes");
    }

    private static JsonNode parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(metadataJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}

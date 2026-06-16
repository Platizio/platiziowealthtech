package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import org.junit.jupiter.api.Test;

class ProductSchemeOrderSupportTest {

    @Test
    void acceptsPoaMfSchemePlanWithGatewayMetadata() {
        ProductScheme scheme = scheme(
                "INF209KA1K47",
                "{\"object\":\"mf_scheme_plan\",\"gateway\":\"cybrillapoa\"}",
                null
        );
        assertThat(ProductSchemeOrderSupport.isPoaOrderable(scheme)).isTrue();
        ProductSchemeOrderSupport.requirePoaOrderable(scheme);
    }

    @Test
    void acceptsIsinWhenMetadataOnlyHasNavReturns() {
        ProductScheme scheme = scheme(
                "INF209KA1K47",
                "{\"nav\":54.32,\"returns\":{\"1y\":12.4}}",
                null
        );
        assertThat(ProductSchemeOrderSupport.isPoaOrderable(scheme)).isTrue();
        ProductSchemeOrderSupport.requirePoaOrderable(scheme);
    }

    @Test
    void acceptsWhenExternalFetchRequestShowsMfSchemePlans() {
        ProductScheme scheme = scheme(
                "INF209KA1K47",
                null,
                "{\"path\":\"/v2/mf_scheme_plans/cybrillapoa\"}"
        );
        assertThat(ProductSchemeOrderSupport.isPoaOrderable(scheme)).isTrue();
    }

    @Test
    void rejectsOmsFundSchemeImport() {
        ProductScheme scheme = scheme(
                "INF001",
                "{\"name\":\"Alpha Fund\"}",
                "{\"path\":\"/api/oms/fund_schemes\"}"
        );
        assertThat(ProductSchemeOrderSupport.isPoaOrderable(scheme)).isFalse();
        assertThatThrownBy(() -> ProductSchemeOrderSupport.requirePoaOrderable(scheme))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OMS fund_schemes");
    }

    @Test
    void displayNameFallsBackToIsinWhenSchemeNameMissing() {
        ProductScheme scheme = scheme("INF209KA1K47", null, null);
        scheme.setSchemeName(null);
        assertThat(ProductSchemeOrderSupport.displayName(scheme)).isEqualTo("INF209KA1K47");
    }

    private ProductScheme scheme(String isin, String metadataJson, String externalFetchRequestJson) {
        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName("Test Fund");
        scheme.setAmcName("Test AMC");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode(isin);
        scheme.setExternalIsin(isin);
        scheme.setActive(true);
        scheme.setMetadataJson(metadataJson);
        scheme.setExternalFetchRequestJson(externalFetchRequestJson);
        return scheme;
    }
}

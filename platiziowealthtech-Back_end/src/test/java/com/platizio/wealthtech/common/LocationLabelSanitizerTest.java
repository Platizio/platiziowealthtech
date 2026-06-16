package com.platizio.wealthtech.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocationLabelSanitizerTest {

    @Test
    void sanitizeStripsSandboxJunkAfterMumbai() {
        assertThat(LocationLabelSanitizer.sanitize("Mumbai%00$##$$@#$T^&*&^%$#"))
                .isEqualTo("Mumbai");
    }

    @Test
    void resolveCityLabelFallsBackToDistrictWhenCityCorrupt() {
        assertThat(LocationLabelSanitizer.resolveCityLabel("Mumbai%00$##$$", "Mumbai"))
                .isEqualTo("Mumbai");
    }

    @Test
    void normalizeStateLabelTitleCasesAllCaps() {
        assertThat(LocationLabelSanitizer.normalizeStateLabel("MAHARASHTRA"))
                .isEqualTo("Maharashtra");
    }

    @Test
    void sanitizeCityOptionsDedupesCleanValues() {
        List<String> cities = LocationLabelSanitizer.sanitizeCityOptions(
                "Mumbai%00garbage",
                "Mumbai",
                List.of("Mumbai%00$##$$", "Mumbai")
        );
        assertThat(cities).containsExactly("Mumbai");
    }
}

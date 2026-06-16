package com.platizio.wealthtech.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PanFormatTest {

    @Test
    void acceptsStandardIndianPan() {
        assertThat(PanFormat.isIndianPan("ABCDE1234F")).isTrue();
        assertThat(PanFormat.isIndianPan("AAAPA3751A")).isTrue();
        assertThat(PanFormat.isIndianPan("GYAPS3751D")).isTrue();
    }

    @Test
    void rejectsInvalidIndianPan() {
        assertThat(PanFormat.isIndianPan("abcde1234f")).isFalse();
        assertThat(PanFormat.isIndianPan("ABCDE123F")).isFalse();
        assertThat(PanFormat.isIndianPan("")).isFalse();
    }

    @Test
    void acceptsOfficialPoaSandboxExamples() {
        assertThat(PanFormat.isPoaSandboxPan("GYAPS3751D")).isTrue();
        assertThat(PanFormat.isPoaSandboxPan("AAAPA3751A")).isTrue();
        assertThat(PanFormat.isPoaSandboxPan("ABCPI1234F")).isTrue();
        assertThat(PanFormat.isPoaSandboxPan("ABCPA1234F")).isTrue();
        assertThat(PanFormat.isPoaSandboxPan("ABCPX3753A")).isTrue();
    }

    @Test
    void rejectsNonSimulatorPanForPoaSandbox() {
        assertThat(PanFormat.isPoaSandboxPan("ABCDE1234F")).isFalse();
        assertThat(PanFormat.isPoaSandboxPan("ADTXPX4103V")).isFalse();
    }

    @Test
    void validateBeforePoaApiEnforcesSandboxPatternOnlyInSandbox() {
        assertThatCode(() -> PanFormat.validateBeforePoaApi("GYAPS3751D", true)).doesNotThrowAnyException();

        assertThatThrownBy(() -> PanFormat.validateBeforePoaApi("ABCDE1234F", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sandbox mode is active");

        assertThatCode(() -> PanFormat.validateBeforePoaApi("ABCDE1234F", false)).doesNotThrowAnyException();
    }

    @Test
    void validateForDatabaseOnlyChecksIndianStructure() {
        assertThatCode(() -> PanFormat.validateForDatabase("ABCDE1234F")).doesNotThrowAnyException();
        assertThatCode(() -> PanFormat.validateForDatabase("GYAPS3751D")).doesNotThrowAnyException();

        assertThatThrownBy(() -> PanFormat.validateForDatabase("bad-pan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PanFormat.INDIAN_PAN_MESSAGE);
    }

    @Test
    void gatewayKycReadinessPatternsMatchDocs() {
        assertThat(PanFormat.isGatewayKycReadyPan("GYAPS3751D")).isTrue();
        assertThat(PanFormat.isGatewayKycUnavailablePan("GYAPS3753D")).isTrue();
    }

    @Test
    void normalizeUppercasesAndTrims() {
        assertThat(PanFormat.normalize("  gyaps3751d ")).isEqualTo("GYAPS3751D");
    }

    @Test
    void detectsSandboxEnvironmentFromBaseUrl() {
        assertThat(PanFormat.isCybrillaSandboxEnvironment("https://api.sandbox.cybrilla.com")).isTrue();
        assertThat(PanFormat.isCybrillaSandboxEnvironment("https://api.cybrilla.com")).isFalse();
    }
}

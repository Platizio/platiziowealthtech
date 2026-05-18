package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DistributorControllerTest {

    @Test
    void distributorControllerDoesNotExposeLegacyLoginEndpoint() {
        assertThat(Arrays.stream(DistributorController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("login");
    }
}

package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ProductControllerTest {

    @Test
    void schemeMutationEndpointsRequireAdminRole() throws NoSuchMethodException {
        Method refreshSchemes = ProductController.class.getMethod("refreshSchemes");
        Method deleteScheme = ProductController.class.getMethod("deleteScheme", UUID.class);

        assertThat(refreshSchemes.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(deleteScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }
}

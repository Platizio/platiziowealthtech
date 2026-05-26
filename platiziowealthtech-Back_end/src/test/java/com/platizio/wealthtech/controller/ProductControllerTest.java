package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.dto.ProductSchemeStatusRequest;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ProductControllerTest {

    @Test
    void schemeMutationEndpointsRequireAdminRole() throws NoSuchMethodException {
        Method createScheme = ProductController.class.getMethod("createScheme", ProductSchemeRequest.class);
        Method updateScheme = ProductController.class.getMethod("updateScheme", UUID.class, ProductSchemeRequest.class);
        Method updateSchemeStatus = ProductController.class.getMethod("updateSchemeStatus", UUID.class, ProductSchemeStatusRequest.class);
        Method refreshSchemes = ProductController.class.getMethod("refreshSchemes");
        Method deleteScheme = ProductController.class.getMethod("deleteScheme", UUID.class);

        assertThat(createScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(updateScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(updateSchemeStatus.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(refreshSchemes.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(deleteScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }
}

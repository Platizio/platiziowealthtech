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
        Method refreshSchemes = ProductController.class.getMethod(
                "refreshSchemes",
                String.class,
                Boolean.class,
                String.class,
                String.class,
                String.class,
                int.class,
                int.class
        );
        Method syncFundsFromCybrilla = ProductController.class.getMethod(
                "syncFundsFromCybrilla",
                String.class,
                Boolean.class,
                String.class,
                String.class,
                String.class,
                int.class,
                int.class
        );
        Method deleteScheme = ProductController.class.getMethod("deleteScheme", UUID.class);

        assertThat(createScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
        assertThat(updateScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
        assertThat(updateSchemeStatus.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
        assertThat(refreshSchemes.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
        assertThat(syncFundsFromCybrilla.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
        assertThat(deleteScheme.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')");
    }
}

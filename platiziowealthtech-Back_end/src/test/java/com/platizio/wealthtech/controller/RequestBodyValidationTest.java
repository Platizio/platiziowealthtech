package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

class RequestBodyValidationTest {

    @Test
    void allControllerRequestBodiesUseValid() {
        List<Class<?>> controllers = List.of(
                AuthController.class,
                DistributorController.class,
                InvestorController.class,
                LeadController.class,
                OrderController.class
        );

        List<String> missingValid = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && !parameter.isAnnotationPresent(Valid.class)) {
                        missingValid.add(controller.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }

        assertThat(missingValid).isEmpty();
    }
}

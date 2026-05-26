package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.service.AuthCookieService;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class SecretFallbackConfigurationTest {

    @Test
    void baseApplicationConfigRequiresProdRelevantSecrets() throws Exception {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(applicationYaml).contains("password: ${DB_PASSWORD}");
        assertThat(applicationYaml).contains("secret: ${JWT_SECRET}");
        assertThat(applicationYaml).contains("cookie-secure: ${AUTH_COOKIE_SECURE}");
        assertThat(applicationYaml).doesNotContain("password: ${DB_PASSWORD:");
        assertThat(applicationYaml).doesNotContain("secret: ${JWT_SECRET:");
        assertThat(applicationYaml).doesNotContain("cookie-secure: ${AUTH_COOKIE_SECURE:");
    }

    @Test
    void localProfileOwnsLocalOnlyFallbacks() throws Exception {
        String localYaml = Files.readString(Path.of("src/main/resources/application-local.yml"));

        assertThat(localYaml).contains("password: ${DB_PASSWORD:1234}");
        assertThat(localYaml).contains("secret: ${JWT_SECRET:local-dev-jwt-secret-32-bytes-minimum-not-for-prod}");
        assertThat(localYaml).contains("cookie-secure: ${AUTH_COOKIE_SECURE:false}");
    }

    @Test
    void authCookieServiceDoesNotProvideCodeLevelCookieSecureFallback() {
        Constructor<?> constructor = AuthCookieService.class.getConstructors()[0];

        assertThat(valueAnnotationFor(constructor, "app.auth.cookie-secure"))
                .isEqualTo("${app.auth.cookie-secure}");
    }

    private String valueAnnotationFor(Constructor<?> constructor, String propertyName) {
        for (Parameter parameter : constructor.getParameters()) {
            for (Annotation annotation : parameter.getAnnotations()) {
                if (annotation instanceof Value value && value.value().contains(propertyName)) {
                    return value.value();
                }
            }
        }
        return null;
    }
}

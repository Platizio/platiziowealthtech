package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTest {

    @Test
    void prometheusRegistryIsConfiguredAndExposed() throws Exception {
        String pomXml = Files.readString(Path.of("pom.xml"));
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(pomXml).contains("<artifactId>micrometer-registry-prometheus</artifactId>");
        assertThat(applicationYaml).contains("include: health,info,prometheus");
        assertThat(applicationYaml).contains("requestId:%X{requestId:-}");
    }

    @Test
    void localProfileEnablesSlowQueryLoggingAndOptInSqlDebugLogging() throws Exception {
        String localYaml = Files.readString(Path.of("src/main/resources/application-local.yml"));

        assertThat(localYaml).contains("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS");
        assertThat(localYaml).contains("org.hibernate.SQL: ${HIBERNATE_SQL_LOG_LEVEL:WARN}");
        assertThat(localYaml).contains("org.hibernate.type.descriptor.sql.BasicBinder: ${HIBERNATE_BIND_LOG_LEVEL:WARN}");
        assertThat(localYaml).contains("org.hibernate.orm.jdbc.bind: ${HIBERNATE_BIND_LOG_LEVEL:WARN}");
    }
}

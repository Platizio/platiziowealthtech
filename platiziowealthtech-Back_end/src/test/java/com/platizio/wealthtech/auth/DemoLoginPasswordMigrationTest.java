package com.platizio.wealthtech.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DemoLoginPasswordMigrationTest {

    @Test
    void demoPasswordHashMatchesFrontendDemoPassword() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V3__seed_data.sql"));
        assertThat(migration).contains("alice@example.com");

        String aliceSection = migration.substring(0, migration.indexOf("a@a.com"));
        Matcher matcher = Pattern.compile("'(\\$2a\\$[^']+)'").matcher(aliceSection);
        assertThat(matcher.find()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("Platizio@2024", matcher.group(1))).isTrue();
    }

    @Test
    void localTestLoginPasswordHashMatchesSharedTestPassword() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V3__seed_data.sql"));
        assertThat(migration).contains("a@a.com");

        String testSection = migration.substring(migration.indexOf("a@a.com"));
        Matcher matcher = Pattern.compile("'(\\$2a\\$[^']+)'").matcher(testSection);
        assertThat(matcher.find()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("Ok@123456", matcher.group(1))).isTrue();
    }
}

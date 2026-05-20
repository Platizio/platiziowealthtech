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
                "src/main/resources/db/migration/V22__align_demo_login_password.sql"));
        Matcher matcher = Pattern.compile("password_hash = '([^']+)'").matcher(migration);

        assertThat(matcher.find()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("Platizio@2024", matcher.group(1))).isTrue();
        assertThat(migration).contains("alice@example.com");
    }

    @Test
    void localTestLoginPasswordHashMatchesSharedTestPassword() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V23__seed_local_test_login.sql"));
        Matcher matcher = Pattern.compile("'(\\$2a\\$[^']+)'").matcher(migration);

        assertThat(matcher.find()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("Ok@123456", matcher.group(1))).isTrue();
        assertThat(migration).contains("a@a.com");
    }
}

package com.platizio.wealthtech.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migration-squash shim.
 *
 * The legacy incremental migrations (V1..V36) were consolidated into V1..V3.
 * Databases that already ran the old set still carry flyway_schema_history rows
 * for the removed versions and the old checksums for V1..V3, which would make
 * the default validate-on-migrate fail and block startup.
 *
 * Running {@code repair()} before {@code migrate()} fixes both cases on Flyway 10:
 *   - it realigns the V1..V3 checksums/descriptions with the new files, and
 *   - it marks the now-absent V4..V36 history rows as deleted so validation
 *     ignores them.
 *
 * This is safe to keep permanently: on a fully-aligned or brand-new database
 * {@code repair()} is a no-op. It can be removed once every environment has
 * booted at least once after the squash.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairBeforeMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}

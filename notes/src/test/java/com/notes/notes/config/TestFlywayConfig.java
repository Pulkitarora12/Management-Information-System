package com.notes.notes.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * mis_test is wiped and rebuilt from V1 on every test run, so test runs never
 * depend on (or accumulate) state left over by a previous run.
 */
@Configuration
@Profile("test")
public class TestFlywayConfig {

    @Bean
    public FlywayMigrationStrategy cleanMigrateStrategy() {
        return (Flyway flyway) -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}

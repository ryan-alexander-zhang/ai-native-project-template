package com.aipersimmon.ddd.flyway;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Opt-in, schema-agnostic Flyway integration for aipersimmon-ddd (Scheme B, shared). Present on the
 * classpath, it applies every discovered component schema at startup with no consumer configuration,
 * each into its own dedicated history table (see {@link AipersimmonFlywayMigrator}).
 *
 * <p>It plugs into Spring Boot's own default Flyway via a {@link FlywayMigrationStrategy} rather than
 * running a competing initializer. Boot's single {@code flywayInitializer} invokes the strategy, which
 * first runs the consumer's own {@code classpath:db/migration} migrations (Boot-configured, default
 * history table, business tables) and then applies each aipersimmon component into its own history
 * table. This means:
 * <ul>
 *   <li>the consumer's own Flyway migrations and {@code spring.flyway.*} configuration are untouched;
 *   <li>ordering is correct by construction (consumer first, then ours) — no bean-ordering games,
 *       no circular dependencies;
 *   <li>{@code @DependsOnDatabaseInitialization} beans (e.g. the process-manager schema validator)
 *       already wait for {@code flywayInitializer}, so they see every table created.
 * </ul>
 *
 * <p>Requires Spring Boot's Flyway auto-configuration to be active (the default when {@code flyway-core}
 * is present). Disable this integration with {@code aipersimmon.ddd.flyway.enabled=false}. If the
 * consumer defines their own {@link FlywayMigrationStrategy}, this one backs off — then they must run
 * the aipersimmon components themselves (call {@link AipersimmonFlywayMigrator#migrate}).
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, FlywayMigrationStrategy.class})
@ConditionalOnProperty(prefix = "aipersimmon.ddd.flyway", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AipersimmonFlywayProperties.class)
public class AipersimmonDddFlywayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FlywayMigrationStrategy aipersimmonFlywayMigrationStrategy(AipersimmonFlywayProperties properties) {
        AipersimmonFlywayMigrator migrator = new AipersimmonFlywayMigrator(properties);
        return flyway -> {
            // 1) the consumer's own default migrations (classpath:db/migration), exactly as Spring
            //    Boot would have run them — same Flyway config, same default history table.
            flyway.migrate();
            // 2) each aipersimmon component, into its own dedicated history table, against the same
            //    DataSource. Runs second, so baseline-on-migrate handles the now-non-empty schema.
            migrator.migrate(flyway.getConfiguration().getDataSource());
        };
    }
}

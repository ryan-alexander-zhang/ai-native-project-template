package com.example.samples.s23.migration;

import com.aipersimmon.ddd.flyway.AipersimmonFlywayMigrator;
import com.aipersimmon.ddd.flyway.AipersimmonFlywayProperties;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Three sets of migrations, one database, one place that decides their order.
 *
 * <p>Spring Boot gives an application exactly one Flyway, configured by {@code spring.flyway.*}, running
 * one location into one history table. This application has three sets: ordering's, billing's, and the
 * framework component schemas that ship inside the aipersimmon jars. So one of them rides Boot's own
 * configuration and the rest need instances of their own — and something has to say what order they run
 * in.
 *
 * <p><strong>The trap this class is the remedy for.</strong> The framework normally installs its own
 * {@link FlywayMigrationStrategy}, which runs the consumer's migrations and then the component ones.
 * That bean is {@code @ConditionalOnMissingBean}: define a strategy of your own — which a second context
 * forces you to do — and the framework's backs off <em>silently</em>, taking the component migrations with
 * it. The application then starts with no {@code aipersimmon_outbox}, and the first command that publishes
 * anything rolls back.
 *
 * <p>It is not actually silent, and that is the only reason this is a trap with a floor rather than a
 * hole: each component ships a schema validator that refuses to start when its tables are missing, naming
 * {@code aipersimmon.ddd.flyway.components}. So the mistake costs a failed boot rather than a broken
 * production. {@code StrategyTrapTest} measures both halves — what a forgetful strategy does, and that the
 * refusal names the property.
 *
 * <p><strong>Why the order below is the only correct one.</strong>
 *
 * <ol>
 *   <li><strong>Ordering, then billing</strong> — not because either depends on the other (they must not:
 *       see billing's V1 on why there is no cross-context foreign key) but because a deterministic order
 *       makes a failure reproducible. Two contexts that migrate in whatever order the container happens to
 *       wire produce a schema that is right on every machine and a failure that is right on one.
 *   <li><strong>The framework last</strong> — which is what the library does too, and for a reason worth
 *       repeating: it runs against a schema that is by then non-empty, so its {@code baselineOnMigrate}
 *       (default true, {@code baselineVersion} 0) has something to baseline. A component migrator that ran
 *       first on a fresh database would work, and would then behave differently the second time.
 * </ol>
 *
 * <p><strong>Why each set has an explicitly named history table.</strong> The default name,
 * {@code flyway_schema_history}, belongs to whoever got there first — and the first context in a codebase
 * always does, because it was alone. The second context then arrives to find the obvious name taken and
 * the first context's history indistinguishable from "the application's". Naming both from the start costs
 * one property and removes that asymmetry; the framework's components are already named
 * {@code flyway_schema_history_aipersimmon_<component>} by the library.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MigrationConfiguration.class);

  /**
   * Runs all three sets, in order, when Boot's {@code flywayInitializer} fires.
   *
   * <p>A strategy rather than three {@code Flyway} beans, because beans give an ordering that has to be
   * asserted and a strategy gives one that can be read. The {@code flyway} handed in is Boot's own,
   * configured from {@code spring.flyway.*} — which this application points at ordering.
   */
  @Bean
  FlywayMigrationStrategy migrations(AipersimmonFlywayProperties frameworkComponents) {
    return flyway -> {
      log.info("s23: applying ordering migrations");
      flyway.migrate();

      log.info("s23: applying billing migrations");
      Flyway.configure()
          .dataSource(flyway.getConfiguration().getDataSource())
          .locations("classpath:db/migration/billing")
          .table("flyway_schema_history_billing")
          // Non-empty by now (ordering's tables are there), so the second context has to be allowed to
          // baseline. This is the same accommodation the framework's migrator makes, and forgetting it is
          // how "it worked on a fresh database" becomes "it fails in every environment that has data".
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .load()
          .migrate();

      log.info("s23: applying aipersimmon component migrations");
      new AipersimmonFlywayMigrator(frameworkComponents)
          .migrate(flyway.getConfiguration().getDataSource());
    };
  }
}

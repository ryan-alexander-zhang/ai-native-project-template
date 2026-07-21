/**
 * Opt-in, schema-agnostic Flyway integration for the aipersimmon-ddd family (Scheme B, shared).
 *
 * <p>Adding this module to the classpath makes the tables of whichever aipersimmon storage modules
 * are present apply themselves at startup with zero configuration. It plugs into Spring Boot's own
 * default Flyway via a {@link
 * org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy}: the consumer's own {@code
 * classpath:db/migration} migrations run first (default history table, their business tables), then
 * {@link com.aipersimmon.ddd.flyway.AipersimmonFlywayMigrator} discovers each component's
 * migrations ({@code classpath:aipersimmon/db/migration/{component}/{vendor}} — kept out of {@code
 * db/migration} so Boot's default Flyway never scans them) and applies each into its own history
 * table ({@code flyway_schema_history_aipersimmon_{component}}). The consumer's own Flyway and
 * {@code spring.flyway.*} configuration are left completely intact.
 */
package com.aipersimmon.ddd.flyway;

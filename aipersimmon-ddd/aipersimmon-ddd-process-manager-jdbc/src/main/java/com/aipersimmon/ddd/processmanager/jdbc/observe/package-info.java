/**
 * Framework-free observability seams for the JDBC Process Manager (design-00004 §5.3): a
 * push-style {@link com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver} the runtime
 * and relay report timing/counter signals to, and a pull-style
 * {@link com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog} that reads backlog
 * SLIs from the four-table store. Neither depends on Micrometer or Spring; the starter binds them
 * to a {@code MeterRegistry} and Actuator health when those are on the classpath.
 */
package com.aipersimmon.ddd.processmanager.jdbc.observe;

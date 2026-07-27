package com.example;

import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Real middleware for the full-context acceptance tests. Imported by every {@code @SpringBootTest}
 * in this module; Spring Boot derives the DataSource and {@code spring.kafka.bootstrap-servers}
 * from the containers and manages their lifecycle, so classes sharing a configuration share one
 * pair of containers through the context cache.
 *
 * <p>Kafka is real on purpose: these tests exercise the broker hop end to end — an integration
 * event is written to the outbox, relayed to the topic, consumed back through the inbox-guarded
 * bridge and republished in process — so the assertions prove the reliable transport, not just the
 * in-process cascade.
 *
 * <p>Both containers come from {@code aipersimmon-ddd-test-support}, which is what {@code
 * CHOOSING-MODULES.md} points a consumer at and which owns the image pins — so this sample cannot
 * drift from the versions the library tests against, and there is no version number to maintain
 * here at all. That is the whole content of this class, and it is the point: the containers an
 * integration test needs are a dependency, not something each application writes for itself.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresServiceConnection.class, KafkaServiceConnection.class})
class TestInfrastructure {}

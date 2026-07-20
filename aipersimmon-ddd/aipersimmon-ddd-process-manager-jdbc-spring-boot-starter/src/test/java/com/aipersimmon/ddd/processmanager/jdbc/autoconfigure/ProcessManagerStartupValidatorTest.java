package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Startup consistency checks: version-in-use, integration-event codec, dispatcher. */
class ProcessManagerStartupValidatorTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcProcessInstanceStore instances;
    private ProcessDefinitionRegistry definitions;
    private ProcessStateCodecRegistry stateCodecs;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        instances = new JdbcProcessInstanceStore(jdbc);
        definitions = new ProcessDefinitionRegistry(List.of(new StarterTestProcess.Definition()));
        stateCodecs = new ProcessStateCodecRegistry(List.of(StarterTestProcess.stateCodec()));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private ProcessManagerStartupValidator validator(
            ProcessPayloadCodecRegistry payloadCodecs, EffectDispatcherRegistry dispatchers, boolean relayEnabled) {
        return new ProcessManagerStartupValidator(
                instances, definitions, stateCodecs, payloadCodecs, dispatchers, relayEnabled);
    }

    private void insertInstance(String definitionVersion, int schemaVersion) {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
        jdbc.update("""
                INSERT INTO aipersimmon_process_instance (
                    instance_id, process_type, business_key, definition_version, state_schema_version,
                    lifecycle, business_step, revision, state_payload_type, state_payload, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                "inst-1", "starter.test", "order-1", definitionVersion, schemaVersion,
                "RUNNING", "GO", 1L, "starter.test.state", "GO", now, now);
    }

    @Test
    void passesWhenEveryReferencedVersionResolves() {
        insertInstance("v1", 1);
        assertDoesNotThrow(() -> validator(empty(), commandOnly(), true).afterPropertiesSet());
    }

    @Test
    void failsWhenALiveInstanceReferencesAMissingDefinitionVersion() {
        insertInstance("v2", 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(empty(), commandOnly(), true).afterPropertiesSet());
        assertTrue(e.getMessage().contains("definition version v2"));
    }

    @Test
    void failsWhenALiveInstanceReferencesAMissingStateSchemaVersion() {
        insertInstance("v1", 2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(empty(), commandOnly(), true).afterPropertiesSet());
        assertTrue(e.getMessage().contains("state schema version 2"));
    }

    @Test
    void failsWhenAnIntegrationEventCodecTypeDisagreesWithEventType() {
        ProcessPayloadCodecRegistry mismatched = new ProcessPayloadCodecRegistry(List.of(
                eventCodec(new PayloadType("wrong.name", 1))));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(mismatched, commandOnly(), false).afterPropertiesSet());
        assertTrue(e.getMessage().contains("@EventType says test.thing-happened"));
    }

    @Test
    void passesWhenTheIntegrationEventCodecTypeMatchesEventType() {
        ProcessPayloadCodecRegistry matched = new ProcessPayloadCodecRegistry(List.of(
                eventCodec(new PayloadType("test.thing-happened", 1))));
        // Relay disabled, so only the codec/@EventType cross-check runs: a matching type passes.
        assertDoesNotThrow(() -> validator(matched, commandOnly(), false).afterPropertiesSet());
    }

    @Test
    void failsWhenTheRelayIsEnabledButNoDispatcherIsRegistered() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(empty(), new EffectDispatcherRegistry(List.of()), true).afterPropertiesSet());
        assertTrue(e.getMessage().contains("no effect dispatcher"));
    }

    @Test
    void failsWhenAnEventCodecExistsButNoPublishDispatcher() {
        ProcessPayloadCodecRegistry matched = new ProcessPayloadCodecRegistry(List.of(
                eventCodec(new PayloadType("test.thing-happened", 1))));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(matched, commandOnly(), true).afterPropertiesSet());
        assertTrue(e.getMessage().contains("PUBLISH_INTEGRATION_EVENT"));
    }

    private static ProcessPayloadCodecRegistry empty() {
        return new ProcessPayloadCodecRegistry(List.of());
    }

    private static EffectDispatcherRegistry commandOnly() {
        return new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(new NoopBus())));
    }

    private static ProcessPayloadCodec<ThingHappened> eventCodec(PayloadType type) {
        return new ProcessPayloadCodec<>() {
            @Override
            public PayloadType payloadType() {
                return type;
            }

            @Override
            public Class<ThingHappened> javaType() {
                return ThingHappened.class;
            }

            @Override
            public EncodedPayload encode(ThingHappened value) {
                return new EncodedPayload(type, new byte[0]);
            }

            @Override
            public ThingHappened decode(EncodedPayload payload) {
                return new ThingHappened("x");
            }
        };
    }

    @EventType(name = "test.thing-happened", version = 1)
    record ThingHappened(String id) implements IntegrationEvent {
    }

    static final class NoopBus implements CommandBus {
        @Override
        public <R> R send(Command<R> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R send(Command<R> command, CommandContext cause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R sendAs(Command<R> command, CommandContext messageContext) {
            return null;
        }
    }
}

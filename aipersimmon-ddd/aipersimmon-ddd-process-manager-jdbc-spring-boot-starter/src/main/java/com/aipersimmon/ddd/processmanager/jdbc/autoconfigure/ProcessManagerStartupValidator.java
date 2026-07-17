package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore.VersionRef;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Startup consistency checks beyond the four-table existence check, so a bad
 * deployment fails fast rather than at the first advance or relay. It verifies:
 *
 * <ul>
 *   <li>every {@code (processType, definitionVersion)} and {@code (processType, stateSchemaVersion)}
 *       referenced by a live instance still has a registered Definition and state codec — so a
 *       Definition/codec is never removed while instances still run under it;</li>
 *   <li>every registered integration-event payload codec's logical type/version matches the event's
 *       {@link EventType}, so a producer's wire type and the codec agree;</li>
 *   <li>the enabled effect kinds each have a dispatcher — the relay never has staged effects it
 *       cannot deliver (the "no two dispatchers per kind" half is enforced by the registry itself).</li>
 * </ul>
 *
 * These run even when {@code schema-validation=none}; only the table-structure check is toggled by
 * that flag.
 */
@DependsOnDatabaseInitialization
public final class ProcessManagerStartupValidator implements InitializingBean {

    private final JdbcProcessInstanceStore instances;
    private final ProcessDefinitionRegistry definitions;
    private final ProcessStateCodecRegistry stateCodecs;
    private final ProcessPayloadCodecRegistry payloadCodecs;
    private final EffectDispatcherRegistry dispatchers;
    private final boolean relayEnabled;

    public ProcessManagerStartupValidator(
            JdbcProcessInstanceStore instances,
            ProcessDefinitionRegistry definitions,
            ProcessStateCodecRegistry stateCodecs,
            ProcessPayloadCodecRegistry payloadCodecs,
            EffectDispatcherRegistry dispatchers,
            boolean relayEnabled) {
        this.instances = instances;
        this.definitions = definitions;
        this.stateCodecs = stateCodecs;
        this.payloadCodecs = payloadCodecs;
        this.dispatchers = dispatchers;
        this.relayEnabled = relayEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        validateVersionsInUse();
        validateIntegrationEventCodecs();
        validateDispatchers();
    }

    private void validateVersionsInUse() {
        for (VersionRef ref : instances.distinctVersionsInUse()) {
            try {
                definitions.resolve(ref.processType(), ref.definitionVersion());
            } catch (RuntimeException missing) {
                throw new IllegalStateException(
                        "a live instance of process '" + ref.processType().value() + "' runs under definition version "
                                + ref.definitionVersion().value() + " but no such Definition is registered; keep the "
                                + "old Definition available while instances still use it", missing);
            }
            try {
                stateCodecs.forState(ref.processType(), ref.stateSchemaVersion());
            } catch (RuntimeException missing) {
                throw new IllegalStateException(
                        "a live instance of process '" + ref.processType().value() + "' runs under state schema version "
                                + ref.stateSchemaVersion().value() + " but no such ProcessStateCodec is registered; keep "
                                + "the old state codec available while instances still use it", missing);
            }
        }
    }

    private void validateIntegrationEventCodecs() {
        for (ProcessPayloadCodec<?> codec : payloadCodecs.codecs()) {
            Class<?> javaType = codec.javaType();
            if (!IntegrationEvent.class.isAssignableFrom(javaType)) {
                continue;
            }
            EventType eventType = javaType.getAnnotation(EventType.class);
            if (eventType == null) {
                throw new IllegalStateException(
                        "integration event " + javaType.getName() + " has a payload codec but is missing @EventType");
            }
            PayloadType payloadType = codec.payloadType();
            if (!payloadType.logicalType().equals(eventType.name()) || payloadType.version() != eventType.version()) {
                throw new IllegalStateException(
                        "payload codec for integration event " + javaType.getName() + " declares logical type "
                                + payloadType.logicalType() + "/v" + payloadType.version() + " but @EventType says "
                                + eventType.name() + "/v" + eventType.version() + "; they must match");
            }
        }
    }

    private void validateDispatchers() {
        if (!relayEnabled) {
            return;
        }
        if (!dispatchers.supports(ProcessEffectKind.DISPATCH_COMMAND)
                && !dispatchers.supports(ProcessEffectKind.PUBLISH_INTEGRATION_EVENT)) {
            throw new IllegalStateException(
                    "the effect relay is enabled but no effect dispatcher is registered; register a CommandBus "
                            + "and/or IntegrationEvents bean, or disable the relay");
        }
        if (hasIntegrationEventCodec() && !dispatchers.supports(ProcessEffectKind.PUBLISH_INTEGRATION_EVENT)) {
            throw new IllegalStateException(
                    "an integration-event payload codec is registered but no PUBLISH_INTEGRATION_EVENT dispatcher is; "
                            + "register an IntegrationEvents bean so published events can be delivered");
        }
    }

    private boolean hasIntegrationEventCodec() {
        return payloadCodecs.codecs().stream().anyMatch(c -> IntegrationEvent.class.isAssignableFrom(c.javaType()));
    }
}

package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Boot slice: with Micrometer and Actuator on the classpath, the starter wires a
 * Micrometer-backed {@link ProcessObserver}, a backlog meter binder, and a health indicator
 * (design-00004 §5.3). It exercises a start → relay poll and asserts the backlog gauges and the
 * claim/dispatch latency timers are recorded, and that health is UP with an empty backlog.
 */
@SpringBootTest(
        classes = ProcessManagerJdbcObservabilityTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
                "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
        })
class ProcessManagerJdbcObservabilityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ProcessDefinition<?> starterDefinition() {
            return new StarterTestProcess.Definition();
        }

        @Bean
        ProcessPayloadCodec<?> beginCodec() {
            return StarterTestProcess.beginCodec();
        }

        @Bean
        ProcessPayloadCodec<?> doThingCodec() {
            return StarterTestProcess.doThingCodec();
        }

        @Bean
        ProcessStateCodec<?> stateCodec() {
            return StarterTestProcess.stateCodec();
        }

        @Bean
        CommandBus commandBus() {
            return new RecordingCommandBus();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    ProcessRuntime runtime;
    @Autowired
    JdbcProcessEffectRelay relay;
    @Autowired
    MeterRegistry registry;
    @Autowired
    ProcessObserver observer;
    @Autowired
    ProcessManagerJdbcMeterBinder meterBinder;
    @Autowired
    ProcessManagerJdbcHealthIndicator health;

    @Test
    void wiresAMicrometerBackedObserver() {
        assertInstanceOf(MicrometerProcessObserver.class, observer);
    }

    @Test
    void meterBinderRegistersTheBacklogGauges() {
        meterBinder.bindTo(registry);
        assertNotNull(registry.find("aipersimmon.process.manager.dead.effects").gauge());
        assertNotNull(registry.find("aipersimmon.process.manager.dead.deadlines").gauge());
        assertNotNull(registry.find("aipersimmon.process.manager.oldest.pending.effect.age").gauge());
        assertNotNull(registry.find("aipersimmon.process.manager.oldest.pending.deadline.age").gauge());
        assertNotNull(registry.find("aipersimmon.process.manager.stuck.instances").gauge());
        assertNotNull(registry.find("aipersimmon.process.manager.suspended.instances").tag("source", "EFFECT").gauge());
    }

    @Test
    void relayRecordsClaimAndDispatchLatencyThroughTheObserver() {
        ProcessAdvanceResult started = runtime.start(
                StarterTestProcess.TYPE, new ProcessBusinessKey("order-obs"),
                new StarterTestProcess.Begin("order-obs"), CommandContext.root("msg-1", null));
        assertEquals(1, relay.pollOnce(), "the staged effect is delivered");

        assertTrue(registry.get("aipersimmon.process.manager.claim.latency").timer().count() >= 1,
                "a claim was timed");
        assertEquals(1, registry.get("aipersimmon.process.manager.dispatch.latency")
                .tag("outcome", "success").timer().count());
        assertNotNull(started.transitionId());
    }

    @Test
    void healthIsUpWithAnEmptyBacklog() {
        assertEquals(Status.UP, health.health().getStatus());
    }

    static final class RecordingCommandBus implements CommandBus {
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

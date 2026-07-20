package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Boot slice: with an ObjectMapper and an explicit ProcessSerializationCatalog (and no hand-written
 * codec beans), the Jackson convenience layer generates the payload and state codecs, and an
 * end-to-end start → relay round-trips the command payload through JSON.
 */
@SpringBootTest(
        classes = ProcessManagerJdbcJacksonCodecTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
                "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
        })
class ProcessManagerJdbcJacksonCodecTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ProcessDefinition<?> starterDefinition() {
            return new StarterTestProcess.Definition();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ProcessSerializationCatalog processSerializationCatalog() {
            return ProcessSerializationCatalog.builder()
                    .payload("starter.begin", 1, StarterTestProcess.Begin.class)
                    .payload("starter.do-thing", 1, StarterTestProcess.DoThing.class)
                    .state(StarterTestProcess.TYPE, new StateSchemaVersion(1),
                            "starter.test.state", StarterTestProcess.St.class)
                    .build();
        }

        @Bean
        CommandBus commandBus() {
            return new RecordingCommandBus();
        }
    }

    @Autowired
    ProcessRuntime runtime;
    @Autowired
    com.aipersimmon.ddd.processmanager.runtime.ProcessQuery query;
    @Autowired
    JdbcProcessEffectRelay relay;
    @Autowired
    CommandBus commandBus;
    @Autowired
    ProcessPayloadCodecRegistry payloadCodecs;

    @Test
    void generatesJacksonCodecsFromTheCatalogAndRoundTripsThroughJson() {
        ProcessAdvanceResult started = runtime.start(
                StarterTestProcess.TYPE, new ProcessBusinessKey("order-json"),
                new StarterTestProcess.Begin("order-json"), CommandContext.root("msg-1", null));

        ProcessView view = query.find(started.processRef()).orElseThrow();
        assertEquals("GO", view.step().value(), "start encoded the state through the Jackson state codec");

        assertEquals(1, relay.pollOnce());
        RecordingCommandBus bus = (RecordingCommandBus) commandBus;
        assertEquals("order-json", ((StarterTestProcess.DoThing) bus.commands.get(0)).reference(),
                "the command payload round-tripped through the Jackson payload codec");
    }

    @Test
    void mapsADecodeFailureToProcessSerializationException() {
        var codec = payloadCodecs.forType(new PayloadType("starter.begin", 1));
        EncodedPayload garbage = new EncodedPayload(
                new PayloadType("starter.begin", 1), "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ProcessSerializationException.class, () -> codec.decode(garbage),
                "a Jackson failure surfaces as the framework serialization exception");
    }

    static final class RecordingCommandBus implements CommandBus {
        final List<Object> commands = new ArrayList<>();

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
            commands.add(command);
            return null;
        }
    }
}

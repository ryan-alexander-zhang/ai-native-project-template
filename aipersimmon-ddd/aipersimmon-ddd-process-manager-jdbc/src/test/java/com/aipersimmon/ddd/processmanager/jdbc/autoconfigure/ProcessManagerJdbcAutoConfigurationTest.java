package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessWorkerScheduler;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Boot slice test: the starter auto-configures the whole runtime against an in-memory H2,
 * collecting the consumer's Definition and codecs, and an end-to-end start → relay delivers the
 * command under the effect's identity. The workers' poll delay is set very high so the background
 * scheduler stays idle and the relay is driven directly.
 */
@SpringBootTest(
    classes = ProcessManagerJdbcAutoConfigurationTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=1h",
    })
class ProcessManagerJdbcAutoConfigurationTest {

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
  }

  @Autowired ProcessRuntime runtime;
  @Autowired com.aipersimmon.ddd.processmanager.runtime.ProcessQuery query;
  @Autowired ProcessEffectRelay relay;
  @Autowired ProcessWorkerScheduler scheduler;
  @Autowired CommandBus commandBus;

  @Test
  void autoConfiguresTheRuntimeAndStartsTheWorkers() {
    assertTrue(scheduler.isRunning(), "the worker scheduler is started by the context lifecycle");
  }

  @Test
  void startsAProcessAndTheRelayDeliversTheCommandUnderTheEffectIdentity() {
    ProcessAdvanceResult started =
        runtime.start(
            StarterTestProcess.TYPE,
            new ProcessBusinessKey("order-1"),
            new StarterTestProcess.Begin("order-1"),
            CommandContext.root("msg-1"));

    ProcessView view = query.find(started.processRef()).orElseThrow();
    assertEquals("GO", view.step().value());

    int delivered = relay.pollOnce();
    assertEquals(1, delivered);

    RecordingCommandBus bus = (RecordingCommandBus) commandBus;
    assertEquals(1, bus.commands.size());
    assertEquals(started.transitionId() + "#0", bus.contexts.get(0).messageId());
    assertEquals("order-1", ((StarterTestProcess.DoThing) bus.commands.get(0)).reference());
  }

  static final class RecordingCommandBus implements CommandBus {
    final List<Object> commands = new ArrayList<>();
    final List<CommandContext> contexts = new ArrayList<>();

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
      contexts.add(messageContext);
      return null;
    }
  }
}

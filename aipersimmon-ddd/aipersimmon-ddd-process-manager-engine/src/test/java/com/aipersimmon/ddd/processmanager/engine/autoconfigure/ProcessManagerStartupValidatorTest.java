package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The declared-payloads reconciliation (issue-00136): a flow registers one codec per payload, and a
 * forgotten registration used to surface as a {@code ProcessSerializationException} inside the
 * first advance that happened to encode it — a live transaction, possibly days later. A definition
 * that declares its payload set moves that discovery to startup.
 */
class ProcessManagerStartupValidatorTest {

  private static final ProcessType TYPE = new ProcessType("sample.flow");

  record Registered(String id) implements ProcessInput {}

  record Forgotten(String id) implements ProcessInput {}

  /** A definition whose decisions never run here; only its declarations matter. */
  private static final class DeclaringDefinition implements ProcessDefinition<String> {
    private final Set<Class<?>> declared;

    DeclaringDefinition(Set<Class<?>> declared) {
      this.declared = declared;
    }

    @Override
    public ProcessType processType() {
      return TYPE;
    }

    @Override
    public Set<Class<?>> declaredPayloads() {
      return declared;
    }

    @Override
    public ProcessDecision<String> start(ProcessInput input, ProcessContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessDecision<String> react(String state, ProcessInput input, ProcessContext context) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RegisteredCodec implements ProcessPayloadCodec<Registered> {
    @Override
    public PayloadType payloadType() {
      return new PayloadType("sample.registered", 1);
    }

    @Override
    public Class<Registered> javaType() {
      return Registered.class;
    }

    @Override
    public EncodedPayload encode(Registered value) {
      return new EncodedPayload(payloadType(), value.id().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Registered decode(EncodedPayload payload) {
      return new Registered(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  private static ProcessManagerStartupValidator validator(ProcessDefinition<?> definition) {
    return new ProcessManagerStartupValidator(
        new InMemoryProcessInstanceStore(),
        new ProcessDefinitionRegistry(List.of(definition)),
        new ProcessStateCodecRegistry(List.of()),
        new ProcessPayloadCodecRegistry(List.of(new RegisteredCodec())),
        new EffectDispatcherRegistry(List.of()),
        false);
  }

  @Test
  void aDeclaredPayloadWithNoCodecFailsTheStartupNamingBoth() {
    ProcessManagerStartupValidator validator =
        validator(new DeclaringDefinition(Set.of(Registered.class, Forgotten.class)));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);

    assertTrue(refused.getMessage().contains(Forgotten.class.getName()), refused.getMessage());
    assertTrue(refused.getMessage().contains(TYPE.value()), refused.getMessage());
  }

  @Test
  void aFullyRegisteredDeclarationPasses() {
    ProcessManagerStartupValidator validator =
        validator(new DeclaringDefinition(Set.of(Registered.class)));

    assertDoesNotThrow(validator::afterPropertiesSet);
  }

  @Test
  void aDefinitionThatDeclaresNothingIsNotValidated() {
    // Declaring is opt-in: an empty set means "not validated", never "must have zero codecs".
    ProcessManagerStartupValidator validator = validator(new DeclaringDefinition(Set.of()));

    assertDoesNotThrow(validator::afterPropertiesSet);
  }
}

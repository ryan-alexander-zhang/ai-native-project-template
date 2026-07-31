package com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A payload carrying a sealed domain type used to force a hand-written codec — putting
 * {@code @JsonTypeInfo} on the domain type is the infrastructure leak the layering forbids, so
 * Jackson seemingly could not know which variant to rebuild. The catalog's mix-in registration
 * dissolves that dilemma (issue-00136): the polymorphism declaration lives on a mix-in class here
 * in infrastructure, the domain type stays clean, and the hand-written codec — with its
 * hand-written parsing bugs — is deleted.
 */
class JacksonProcessCodecMixInTest {

  /** Stands in for a sealed domain type: no serialization annotation anywhere on it. */
  sealed interface Reason {}

  record Unavailable(String failureId) implements Reason {}

  record Declined(String declineId, String releaseId) implements Reason {}

  record Cancel(String orderId, Reason reason) implements ProcessInput {}

  /** The whole point: this class, not the domain type, carries the wire discriminators. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Unavailable.class, name = "UNAVAILABLE"),
    @JsonSubTypes.Type(value = Declined.class, name = "DECLINED")
  })
  interface ReasonMixIn {}

  /** A mix-in that forgot one of the sealed type's variants. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes(@JsonSubTypes.Type(value = Unavailable.class, name = "UNAVAILABLE"))
  interface IncompleteReasonMixIn {}

  private final ObjectMapper shared = new ObjectMapper();

  private ProcessPayloadCodecRegistry registryFor(ProcessSerializationCatalog catalog) {
    return new JacksonProcessCodecConfiguration()
        .processPayloadCodecRegistry(noExplicitCodecs(), catalog, shared);
  }

  @Test
  void aMixInLetsAPolymorphicPayloadRoundTripWithoutAnnotatingTheDomainType() {
    ProcessPayloadCodecRegistry registry =
        registryFor(
            ProcessSerializationCatalog.builder()
                .payload("sample.cancel", 1, Cancel.class)
                .mixIn(Reason.class, ReasonMixIn.class)
                .build());

    ProcessPayloadCodec<Cancel> codec = registry.forJavaType(Cancel.class);
    Cancel original = new Cancel("order-1", new Declined("d-1", "r-1"));

    assertEquals(original, codec.decode(codec.encode(original)));
  }

  @Test
  void theWireCarriesTheDeclaredDiscriminatorNotTheClassName() {
    ProcessPayloadCodecRegistry registry =
        registryFor(
            ProcessSerializationCatalog.builder()
                .payload("sample.cancel", 1, Cancel.class)
                .mixIn(Reason.class, ReasonMixIn.class)
                .build());

    EncodedPayload encoded =
        registry.forJavaType(Cancel.class).encode(new Cancel("order-1", new Unavailable("f-1")));

    String json = new String(encoded.data(), StandardCharsets.UTF_8);
    assertEquals(
        true,
        json.contains("\"UNAVAILABLE\""),
        "a persisted payload must name its stable discriminator, not a Java class: " + json);
    assertEquals(false, json.contains("Unavailable$"), json);
  }

  @Test
  void theSharedMapperIsNeverMutated() {
    registryFor(
        ProcessSerializationCatalog.builder()
            .payload("sample.cancel", 1, Cancel.class)
            .mixIn(Reason.class, ReasonMixIn.class)
            .build());

    // The application's mapper also serializes HTTP responses; a persistence-only polymorphism
    // declaration leaking into it would change every payload that mentions the type.
    assertNull(shared.findMixInClassFor(Reason.class));
  }

  @Test
  void aMixInThatMissesASealedVariantFailsAtStartupNamingIt() {
    // Jackson would encode the unmapped variant under a fallback simple-class-name id — a success
    // inside the advance transaction whose failure surfaces only when the relay decodes the row.
    // The sealed type's permitted set is known to the compiler, so the registry refuses to build
    // until the mix-in covers it: earlier and more total than the deleted hand-written codec's
    // encode-time default branch.
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                registryFor(
                    ProcessSerializationCatalog.builder()
                        .payload("sample.cancel", 1, Cancel.class)
                        .mixIn(Reason.class, IncompleteReasonMixIn.class)
                        .build()));

    assertEquals(
        true, refused.getMessage().contains(Declined.class.getName()), refused.getMessage());
  }

  @Test
  void twoMixInsForOneTargetAreARegistrationContradiction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProcessSerializationCatalog.builder()
                .mixIn(Reason.class, ReasonMixIn.class)
                .mixIn(Reason.class, Object.class));
  }

  private static ObjectProvider<ProcessPayloadCodec<?>> noExplicitCodecs() {
    return new ObjectProvider<>() {
      @Override
      public ProcessPayloadCodec<?> getObject() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Stream<ProcessPayloadCodec<?>> orderedStream() {
        return Stream.empty();
      }
    };
  }
}

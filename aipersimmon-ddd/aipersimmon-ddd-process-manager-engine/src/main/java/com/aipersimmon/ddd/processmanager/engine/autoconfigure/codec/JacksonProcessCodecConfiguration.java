package com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.AipersimmonDddProcessManagerAutoConfiguration;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Optional Jackson convenience layer: when an {@link ObjectMapper} and an explicit {@link
 * ProcessSerializationCatalog} are both present, it builds the payload and state codec registries
 * from the consumer's explicitly-declared {@code ProcessPayloadCodec} / {@code ProcessStateCodec}
 * beans <em>plus</em> Jackson codecs generated for each catalog entry. It runs before the core
 * auto-configuration so its registries win over the plain ones; the registries still fail fast on
 * any duplicate logical type/version between an explicit codec and a catalog entry. An application
 * needing encryption, upcasting, or a non-JSON format simply declares its own codec beans and omits
 * the catalog.
 */
@AutoConfiguration(
    after = JacksonAutoConfiguration.class,
    before = AipersimmonDddProcessManagerAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnBean({ObjectMapper.class, ProcessSerializationCatalog.class})
public class JacksonProcessCodecConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ProcessPayloadCodecRegistry processPayloadCodecRegistry(
      ObjectProvider<ProcessPayloadCodec<?>> explicit,
      ProcessSerializationCatalog catalog,
      ObjectMapper mapper) {
    ObjectMapper codecMapper = codecMapper(catalog, mapper);
    List<ProcessPayloadCodec<?>> all = new ArrayList<>(explicit.orderedStream().toList());
    for (ProcessSerializationCatalog.PayloadEntry entry : catalog.payloads()) {
      all.add(payloadCodec(entry, codecMapper));
    }
    return new ProcessPayloadCodecRegistry(all);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessStateCodecRegistry processStateCodecRegistry(
      ObjectProvider<ProcessStateCodec<?>> explicit,
      ProcessSerializationCatalog catalog,
      ObjectMapper mapper) {
    ObjectMapper codecMapper = codecMapper(catalog, mapper);
    List<ProcessStateCodec<?>> all = new ArrayList<>(explicit.orderedStream().toList());
    for (ProcessSerializationCatalog.StateEntry entry : catalog.states()) {
      all.add(stateCodec(entry, codecMapper));
    }
    return new ProcessStateCodecRegistry(all);
  }

  /**
   * The mapper the generated codecs encode with. When the catalog declares mix-ins they go onto a
   * <em>copy</em>: the application's mapper also serializes HTTP responses and whatever else, and a
   * persistence-only polymorphism declaration must not leak into those. Without mix-ins the shared
   * mapper is used as-is.
   */
  private static ObjectMapper codecMapper(
      ProcessSerializationCatalog catalog, ObjectMapper mapper) {
    if (catalog.mixIns().isEmpty()) {
      return mapper;
    }
    catalog.mixIns().forEach(JacksonProcessCodecConfiguration::requireSealedCoverage);
    ObjectMapper copy = mapper.copy();
    catalog.mixIns().forEach(copy::addMixIn);
    return copy;
  }

  /**
   * A sealed target's mix-in must map <em>every</em> permitted variant. Jackson's own behaviour for
   * an unmapped subtype is the trap this closes: serialization falls back to the class's simple
   * name — so the encode succeeds inside the advance transaction, and the failure surfaces as an
   * unresolvable type id when the <em>relay</em> decodes the row, a poison effect long after the
   * cause is gone. Because the target is sealed, the compiler knows the complete variant set, and
   * this check makes the registry refuse to build until the mix-in covers it — the same "wrong
   * claim inexpressible" stance the rest of the framework takes, one step earlier than the
   * hand-written codec's encode-time refusal this route replaces (issue-00136).
   *
   * <p>A non-sealed target cannot be enumerated, so it is trusted as declared.
   */
  private static void requireSealedCoverage(Class<?> target, Class<?> mixInSource) {
    if (!target.isSealed()) {
      return;
    }
    JsonSubTypes subTypes = mixInSource.getAnnotation(JsonSubTypes.class);
    Set<Class<?>> mapped =
        subTypes == null
            ? Set.of()
            : Arrays.stream(subTypes.value())
                .map(JsonSubTypes.Type::value)
                .collect(Collectors.toSet());
    List<String> missing =
        Arrays.stream(target.getPermittedSubclasses())
            .filter(variant -> !mapped.contains(variant))
            .map(Class::getName)
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "the mix-in "
              + mixInSource.getName()
              + " for sealed type "
              + target.getName()
              + " does not map every permitted variant — an unmapped one would encode under a "
              + "fallback name and poison the relay at decode. Missing: "
              + String.join(", ", missing));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> JacksonPayloadCodec<T> payloadCodec(
      ProcessSerializationCatalog.PayloadEntry entry, ObjectMapper mapper) {
    return new JacksonPayloadCodec<>(entry.type(), (Class<T>) entry.javaType(), mapper);
  }

  @SuppressWarnings("unchecked")
  private static <S> JacksonStateCodec<S> stateCodec(
      ProcessSerializationCatalog.StateEntry entry, ObjectMapper mapper) {
    return new JacksonStateCodec<>(
        entry.processType(),
        entry.schemaVersion(),
        entry.type(),
        (Class<S>) entry.javaType(),
        mapper);
  }
}

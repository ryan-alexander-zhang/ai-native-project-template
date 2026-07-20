package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.AipersimmonDddProcessManagerJdbcAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Optional Jackson convenience layer: when an {@link ObjectMapper} and an
 * explicit {@link ProcessSerializationCatalog} are both present, it builds the payload and state
 * codec registries from the consumer's explicitly-declared {@code ProcessPayloadCodec} /
 * {@code ProcessStateCodec} beans <em>plus</em> Jackson codecs generated for each catalog entry. It
 * runs before the core auto-configuration so its registries win over the plain ones; the registries
 * still fail fast on any duplicate logical type/version between an explicit codec and a catalog
 * entry. An application needing encryption, upcasting, or a non-JSON format simply declares its own
 * codec beans and omits the catalog.
 */
@AutoConfiguration(
        after = JacksonAutoConfiguration.class,
        before = AipersimmonDddProcessManagerJdbcAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnBean({ObjectMapper.class, ProcessSerializationCatalog.class})
public class JacksonProcessCodecConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProcessPayloadCodecRegistry processPayloadCodecRegistry(
            ObjectProvider<ProcessPayloadCodec<?>> explicit,
            ProcessSerializationCatalog catalog, ObjectMapper mapper) {
        List<ProcessPayloadCodec<?>> all = new ArrayList<>(explicit.orderedStream().toList());
        for (ProcessSerializationCatalog.PayloadEntry entry : catalog.payloads()) {
            all.add(payloadCodec(entry, mapper));
        }
        return new ProcessPayloadCodecRegistry(all);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessStateCodecRegistry processStateCodecRegistry(
            ObjectProvider<ProcessStateCodec<?>> explicit,
            ProcessSerializationCatalog catalog, ObjectMapper mapper) {
        List<ProcessStateCodec<?>> all = new ArrayList<>(explicit.orderedStream().toList());
        for (ProcessSerializationCatalog.StateEntry entry : catalog.states()) {
            all.add(stateCodec(entry, mapper));
        }
        return new ProcessStateCodecRegistry(all);
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
                entry.processType(), entry.schemaVersion(), entry.type(), (Class<S>) entry.javaType(), mapper);
    }
}

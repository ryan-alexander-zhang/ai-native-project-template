package com.example.samples.s05.catalog.adapter;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The consumer's own container and error policy — the part the framework's transport would have brought,
 * and which a foreign format makes the application's business.
 *
 * <p>Three decisions worth naming, because Spring Kafka's defaults are wrong for a mirror and wrong
 * quietly:
 *
 * <ul>
 *   <li><strong>A recoverer at all.</strong> The stock behaviour is to retry a failed record ten times
 *       with no backoff and then <em>log and move on</em>. For master data that is silent loss: the
 *       product simply keeps the old price and nothing anywhere says so. Publishing to
 *       {@code <topic>.DLT} makes the loss an artefact somebody can find and replay.
 *   <li><strong>Bounded retries with backoff.</strong> Two attempts, one second apart. Permanent failures
 *       are already routed straight to the dead letter by the listener's classification, so retries here
 *       exist only for the transient case, and a long ladder would hold the partition — and every other
 *       product's updates behind it — hostage to one row.
 *   <li><strong>{@code <topic>.DLT} must exist.</strong> The recoverer publishes to it; it does not
 *       create it. Missing, the publish fails, the error handler seeks back, and the partition retries
 *       the same record forever with consumer lag as the only symptom. Provisioning it is the
 *       deployment's job, exactly as it is for the framework's own bridge.
 * </ul>
 *
 * <p>The container factory is named rather than replacing the auto-configured one, so this policy applies
 * to the ERP listener and to nothing else. A second integration will want its own answer.
 */
@Configuration(proxyBeanMethods = false)
class ErpConsumerConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> erpListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    // The destination is named explicitly rather than inherited. Spring Kafka's own default suffix is
    // not the `.DLT` the framework's bridge uses — this sample was written assuming it was, and the
    // records went to `<topic>-dlt` instead, where nothing was watching for them. A dead-letter topic
    // whose name is a default is a dead-letter topic somebody will fail to find, so: one convention,
    // stated once, matching the framework's so an operator has one rule to remember. The partition is
    // carried over so a record's ordering context survives the hop.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    // The listener has already decided what is permanent; this is where that decision is honoured.
    // Registering the type here rather than inferring from the exception's shape is what keeps the
    // classification in one place — the boundary that knows what it was translating.
    errorHandler.addNotRetryableExceptions(UntranslatableMessageException.class);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}

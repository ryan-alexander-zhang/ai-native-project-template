package com.example.samples.s22.ordering;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A broker configured the way a production broker is: <strong>topic auto-creation off</strong>.
 *
 * <p>Not the shared {@code KafkaServiceConnection} from the test-support module, and the difference is
 * the sample. With auto-creation on — the broker default, and what every other sample here runs
 * against — publishing to a topic nobody provisioned silently succeeds, creating a one-partition topic
 * with the cluster's default retention that no consumer is subscribed to. Nothing fails, so nothing is
 * operable: there is no dead letter to triage, no alert, and the messages sit on a topic that will be
 * deleted by whoever notices it later.
 *
 * <p>Turning it off is what makes a missing topic a <em>failure</em>, which is the only form in which
 * a misconfiguration can be handled. Every claim this module makes about dead letters depends on it,
 * and so does the consumer module's claim about {@code <topic>.DLT} having to exist beforehand — with
 * auto-creation on, that whole hazard is invisible until the one environment that has it disabled.
 */
@TestConfiguration(proxyBeanMethods = false)
class StrictKafka {

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse(ContainerImages.KAFKA))
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  }
}

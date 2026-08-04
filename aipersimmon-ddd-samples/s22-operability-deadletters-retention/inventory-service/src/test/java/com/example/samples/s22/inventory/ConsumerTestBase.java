package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/** Container wiring, partition-assignment waiting, and the two things every test here reads. */
abstract class ConsumerTestBase {

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry registry;

  @Value("${inventory.ordering-events-topic}")
  protected String topic;

  private KafkaProducer<String, String> producer;

  /**
   * Waits until the bridge's container owns its partition before anything is produced.
   *
   * <p>Not ceremony, and this module needs it more than most: half the assertions here are of the form
   * "and nothing else was consumed". A consumer that was never assigned a partition satisfies every one
   * of them, for the wrong reason. S4 learned this the hard way.
   */
  @BeforeEach
  void awaitAssignmentAndResetState() {
    for (MessageListenerContainer container : registry.getListenerContainers()) {
      ContainerTestUtils.waitForAssignment(container, 1);
    }
    jdbc.update("UPDATE s22_stock SET available = 100, reserved = 0");
    jdbc.update("DELETE FROM aipersimmon_inbox");
    producer = WireRecords.producer(kafka.getBootstrapServers());
  }

  protected void send(ProducerRecord<String, String> record) {
    WireRecords.send(producer, record);
  }

  protected int reserved(String sku) {
    Integer reserved =
        jdbc.queryForObject(
            "SELECT reserved FROM s22_stock WHERE sku = ?", Integer.class, sku);
    return reserved == null ? 0 : reserved;
  }

  protected long inboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }

  /** Whether a topic exists on the broker — the question a missing {@code .DLT} turns into. */
  protected boolean topicExists(String name) {
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers())))) {
      Set<String> names = admin.listTopics().names().get();
      return names.contains(name);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException("could not list topics", e);
    }
  }

  /** Everything currently on a topic, read from the beginning by a group of its own. */
  protected List<ConsumerRecord<String, String>> drain(String name) {
    assertThat(topicExists(name)).as("topic %s does not exist", name).isTrue();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
            new StringDeserializer(),
            new StringDeserializer())) {
      consumer.subscribe(List.of(name));
      List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
      for (int emptyPolls = 0; emptyPolls < 3; ) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
        if (polled.isEmpty()) {
          emptyPolls++;
          continue;
        }
        emptyPolls = 0;
        polled.records(name).forEach(collected::add);
      }
      return collected;
    }
  }
}

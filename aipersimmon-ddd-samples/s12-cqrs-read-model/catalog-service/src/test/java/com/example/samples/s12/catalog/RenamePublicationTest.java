package com.example.samples.s12.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s12.catalog.application.RenameProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The publishing half of the contract, asserted where it is decided: in the outbox row.
 *
 * <p>No broker here. The relay is off, so the row stays put and can be read — which is the library's own
 * documented way to test a publication, and it isolates "did this command decide to publish, and what" from
 * "did the transport deliver it". The consumer's test on the other side starts from a record of exactly this
 * shape.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "aipersimmon.ddd.outbox.relay.enabled=false")
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class RenamePublicationTest {

  private static final String KEYBOARD = "sku-keyboard";

  @Autowired private CommandBus commandBus;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update(
        "UPDATE s12_product SET name = 'Mechanical Keyboard', version = version + 1 WHERE sku = ?",
        KEYBOARD);
  }

  @Test
  void arenameWritesTheProductAndItsAnnouncementTogether() {
    commandBus.send(new RenameProduct(KEYBOARD, "Keyboard Pro"));

    assertThat(nameOf(KEYBOARD)).isEqualTo("Keyboard Pro");
    assertThat(outboxCount()).isEqualTo(1);
    // The wire identity is the logical type, not the Java class name — which is what lets the consumer
    // declare its own class for this contract.
    assertThat(jdbc.queryForObject("SELECT type FROM aipersimmon_outbox", String.class))
        .isEqualTo("com.example.samples.catalog.ProductRenamed");
    // The new name travels with the event. The alternative — "sku-keyboard changed, come and ask" — turns
    // every rename into a fan-out of synchronous calls back into this service.
    assertThat(jdbc.queryForObject("SELECT payload FROM aipersimmon_outbox", String.class))
        .contains("Keyboard Pro");
    // The partition key, so two renames of the same product cannot be consumed out of order.
    assertThat(jdbc.queryForObject("SELECT subject FROM aipersimmon_outbox", String.class))
        .isEqualTo(KEYBOARD);
  }

  @Test
  void arenameToTheSameNameAnnouncesNothing() {
    commandBus.send(new RenameProduct(KEYBOARD, "Mechanical Keyboard"));

    // An at-least-once caller retrying its request must not produce a broadcast every consumer has to absorb.
    // The aggregate decides this, and the handler simply does not publish; the outbox is where it shows.
    assertThat(outboxCount()).isZero();
  }

  @Test
  void twoRenamesAnnounceTwice() {
    commandBus.send(new RenameProduct(KEYBOARD, "Keyboard Pro"));
    commandBus.send(new RenameProduct(KEYBOARD, "Keyboard Pro Max"));

    assertThat(outboxCount()).isEqualTo(2);
    assertThat(nameOf(KEYBOARD)).isEqualTo("Keyboard Pro Max");
  }

  private String nameOf(String sku) {
    return jdbc.queryForObject("SELECT name FROM s12_product WHERE sku = ?", String.class, sku);
  }

  private long outboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class);
  }
}

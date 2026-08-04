package com.example.samples.s12.ordering;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s12.ordering.application.BrowseOrderList;
import com.example.samples.s12.ordering.application.OrderListItem;
import com.example.samples.s12.ordering.application.PlaceOrder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One context and one PostgreSQL for the tests that do not need a broker.
 *
 * <p>The Kafka consumer is off here, which is a statement about what these tests cover rather than a
 * convenience: everything in this class's subclasses is about the projection's own behaviour — placing,
 * paying, rebuilding, and the ripple from a rename — and all of it happens on this side of the wire. The
 * record actually arriving over a topic is a different question with its own test, which does boot a broker.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "aipersimmon.ddd.messaging.kafka.consumer.enabled=false")
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class ReadModelTestBase {

  protected static final String CUSTOMER = "customer-1";
  protected static final String KEYBOARD = "sku-keyboard";
  protected static final String MOUSE = "sku-mouse";
  /** Present in the catalogue, deliberately absent from this context's replica. */
  protected static final String MONITOR = "sku-monitor";

  @Autowired protected CommandBus commandBus;
  @Autowired protected QueryBus queryBus;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void resetEverythingDerived() {
    jdbc.update("DELETE FROM s12_order_list");
    jdbc.update("DELETE FROM s12_order_line");
    jdbc.update("DELETE FROM s12_order");
    jdbc.update("DELETE FROM aipersimmon_inbox");
    // The replica back to its seeded state: two of the catalogue's three products.
    jdbc.update("DELETE FROM s12_product_name");
    jdbc.update(
        "INSERT INTO s12_product_name (sku, name, updated_at) VALUES"
            + " ('sku-keyboard', 'Mechanical Keyboard', now()),"
            + " ('sku-mouse', 'Wireless Mouse', now())");
  }

  protected String placeOrder(String... skus) {
    List<PlaceOrder.Line> lines =
        java.util.Arrays.stream(skus).map(sku -> new PlaceOrder.Line(sku, 1, 1500)).toList();
    return commandBus.send(new PlaceOrder(CUSTOMER, lines));
  }

  protected List<OrderListItem> list() {
    return queryBus.ask(new BrowseOrderList(CUSTOMER, 20));
  }

  protected String summaryOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT display_summary FROM s12_order_list WHERE order_id = ?", String.class, orderId);
  }

  protected Instant projectedAtOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT projected_at FROM s12_order_list WHERE order_id = ?", Instant.class, orderId);
  }

  protected List<String> frozenNamesOf(String orderId) {
    return jdbc.queryForList(
        "SELECT name_at_purchase FROM s12_order_line WHERE order_id = ? ORDER BY id",
        String.class,
        orderId);
  }

  protected long orderRowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s12_order", Long.class);
  }

  protected long projectionRowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s12_order_list", Long.class);
  }
}

package com.example.samples.s08;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** One context, one container, shared by every test class here. */
@SpringBootTest
@Import({PostgresServiceConnection.class, FlakyOnce.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class InventoryTestBase {

  @Autowired protected CommandBus commandBus;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void resetInventory() {
    jdbc.update("DELETE FROM s08_stock");
    jdbc.update("DELETE FROM s08_reservation_budget");
    jdbc.update("INSERT INTO s08_stock (sku, available) VALUES ('SKU-A', 100)");
    jdbc.update("INSERT INTO s08_stock (sku, available) VALUES ('SKU-B', 100)");
    jdbc.update(
        "INSERT INTO s08_reservation_budget (id, limit_units, reserved_units)"
            + " VALUES ('warehouse-1', 30, 0)");
  }

  protected int availableOf(String sku) {
    return jdbc.queryForObject(
        "SELECT available FROM s08_stock WHERE sku = ?", Integer.class, sku);
  }

  protected long versionOf(String sku) {
    return jdbc.queryForObject("SELECT version FROM s08_stock WHERE sku = ?", Long.class, sku);
  }

  protected int reservedUnits() {
    return jdbc.queryForObject(
        "SELECT reserved_units FROM s08_reservation_budget WHERE id = 'warehouse-1'", Integer.class);
  }
}

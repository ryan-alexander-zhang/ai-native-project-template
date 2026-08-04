package com.example.samples.s24;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s24.coupons.application.IssueCoupon;
import com.example.samples.s24.ordering.application.OrderTotals;
import com.example.samples.s24.ordering.application.PlaceOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** One context, one PostgreSQL, three bounded contexts in one process. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class ContextTestBase {

  protected static final String GBP = "GBP";

  @Autowired protected CommandBus commandBus;
  @Autowired protected QueryBus queryBus;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM s24_coupons_redemption");
    jdbc.update("DELETE FROM s24_coupons_coupon");
    jdbc.update("DELETE FROM s24_ordering_order_line");
    jdbc.update("DELETE FROM s24_ordering_order");
    jdbc.update("DELETE FROM s24_inventory_stock_item");
  }

  /** A coupon good for {@code percent} off, valid now, usable {@code times} times. */
  protected void issuePercentCoupon(String code, int percent, int times) {
    commandBus.send(
        new IssueCoupon(
            code,
            percent,
            null,
            GBP,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3_600),
            times));
  }

  protected void issueFixedCoupon(String code, long minorOff, int times) {
    commandBus.send(
        new IssueCoupon(
            code,
            null,
            minorOff,
            GBP,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3_600),
            times));
  }

  protected void issueExpiredCoupon(String code, int percent) {
    commandBus.send(
        new IssueCoupon(
            code,
            percent,
            null,
            GBP,
            Instant.now().minusSeconds(7_200),
            Instant.now().minusSeconds(3_600),
            5));
  }

  /** One line, one unit, at {@code unitMinor}, with an optional coupon. */
  protected OrderTotals placeOrder(String orderId, long unitMinor, String couponCode) {
    return commandBus.send(
        new PlaceOrder(
            orderId,
            "cust-1",
            GBP,
            couponCode,
            List.of(new PlaceOrder.Line("SKU-1", 1, unitMinor))));
  }

  protected Map<String, Object> couponRow(String code) {
    return jdbc.queryForMap("SELECT * FROM s24_coupons_coupon WHERE code = ?", code);
  }

  protected Map<String, Object> orderRow(String id) {
    return jdbc.queryForMap("SELECT * FROM s24_ordering_order WHERE id = ?", id);
  }

  protected int redemptionCount(String code) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM s24_coupons_redemption WHERE coupon_code = ?", Integer.class, code);
  }
}

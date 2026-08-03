package com.example.samples.s11;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trigger itself: nobody calls the sweep, and the order still closes.
 *
 * <p>Its own context, because the property that switches the trigger on is the thing under test —
 * every other test here runs with it off and drives the work directly. That is the split working as
 * intended, not a workaround: a schedule welded into the work would leave both untestable, one for
 * needing a clock and the other for needing a wait.
 *
 * <p>{@code await()} rather than {@code sleep}: the assertion is "eventually closed", and a fixed
 * sleep either makes the suite slow or makes it flaky, usually both in turn.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ordering.sweep.enabled=true", "ordering.sweep.poll-delay-ms=100"})
@Import({PostgresServiceConnection.class, Instruments.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ScheduleTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Instruments.TestClock clock;

  @Test
  void theScheduleClosesAnOverdueOrderWithNobodyAskingIt() {
    jdbc.update("DELETE FROM s11_order");
    ResponseEntity<String> created =
        http.postForEntity(
            "/orders", Map.of("customerId", "customer-1", "payWithinSeconds", 60), String.class);
    String orderId = JsonPath.read(created.getBody(), "$.id");

    clock.advance(Duration.ofSeconds(61));

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo("CLOSED"));
  }

  private String statusOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM s11_order WHERE id = ?", String.class, orderId);
  }
}

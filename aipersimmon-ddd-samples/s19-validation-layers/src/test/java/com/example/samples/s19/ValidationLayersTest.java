package com.example.samples.s19;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s19.ordering.application.PlaceOrder;
import com.example.samples.s19.ordering.application.PlaceOrderInternally;
import com.example.samples.s19.ordering.domain.OrderingErrorCode;
import com.jayway.jsonpath.JsonPath;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Three kinds of refusal, three mechanisms, and the placement that is the point of the middle one. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresServiceConnection.class, ObservingTheLayers.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ValidationLayersTest {

  @Autowired private CommandBus commandBus;
  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObservingTheLayers.Log log;
  @Autowired private ObservingTheLayers.ObservedCalendar calendar;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s19_order");
    log.reset();
    calendar.open();
  }

  @Test
  void theRequestShapeIsRefusedBeforeAnythingIsBuiltFromIt() {
    ResponseEntity<String> response =
        http.postForEntity("/orders", Map.of("customerId", "", "quantity", 0), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(response.getBody(), "$.type")).isEqualTo("about:blank");
    assertThat(JsonPath.<List<?>>read(response.getBody(), "$.errors")).isNotEmpty();
    // Nothing downstream was consulted: this never became a command.
    assertThat(log.observations()).isEmpty();
    assertThat(orderCount()).isZero();
  }

  @Test
  void thesameShapeIsCheckedAgainForEntriesThatAreNotHttp() {
    // The command carries its own constraints, so a scheduler or a message consumer gets the check
    // without the web layer. The duplication with the DTO is the feature.
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("", 5)))
        .isInstanceOf(ConstraintViolationException.class);

    assertThat(log.observations()).isEmpty();
    assertThat(orderCount()).isZero();
  }

  @Test
  void theprecheckRunsOutsideTheTransactionAndTheHandlerInsideIt() {
    commandBus.send(new PlaceOrder("customer-1", 5));

    // The placement, observed rather than argued: both advisory sources were consulted with no
    // transaction open, and by the time the innermost interceptor ran there was one. That is what
    // stops a remote call from holding a database connection while it waits.
    assertThat(log.insideTransactionAt("precheck:customer-standing")).isFalse();
    assertThat(log.insideTransactionAt("precheck:warehouse-calendar")).isFalse();
    assertThat(log.insideTransactionAt("handler")).isTrue();
    assertThat(log.order())
        .containsExactly(
            "precheck:customer-standing", "precheck:warehouse-calendar", "handler");
    assertThat(orderCount()).isEqualTo(1);
  }

  @Test
  void ablockedCustomerIsRefusedAndTheHandlerNeverRuns() {
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("blocked-1", 5)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.CUSTOMER_BLOCKED));

    // The first precheck refused, so the second was never asked and the handler never ran — no
    // transaction was opened for a command that could not succeed.
    assertThat(log.order()).containsExactly("precheck:customer-standing");
    assertThat(orderCount()).isZero();
  }

  @Test
  void everyPrecheckRunsInOrderAndTheFirstRefusalWins() {
    calendar.close();

    // The customer is fine, so the first precheck passes and the second refuses.
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("customer-1", 5)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.WAREHOUSE_CLOSED));

    assertThat(log.order())
        .containsExactly("precheck:customer-standing", "precheck:warehouse-calendar");
    assertThat(orderCount()).isZero();
  }

  @Test
  void bothRefusalsAtOnceStillYieldTheFirstOne() {
    calendar.close();

    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("blocked-1", 5)))
        .extracting(thrown -> ((DomainException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.CUSTOMER_BLOCKED));

    // Bean order decides which of two true refusals the client is told about.
    assertThat(log.order()).containsExactly("precheck:customer-standing");
  }

  @Test
  void theAggregateRefusesWhatNoPrecheckScreened() {
    // An internal path with no prechecks registered against its command type. Prechecks are advisory
    // by construction — this is what is left when nothing screened the command first, and it is the
    // only one of the three that is a guarantee.
    assertThatThrownBy(() -> commandBus.send(new PlaceOrderInternally("customer-1", 500)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("may not exceed 100 units");

    assertThat(log.order()).containsExactly("handler");
    assertThat(orderCount()).isZero();
  }

  @Test
  void thesameOverCapOrderIsRefusedOnTheScreenedPathToo() {
    // The precheck path does not screen for this rule at all, so the aggregate refuses it there as
    // well: a rule enforced in one layer only needs to be in the layer that owns the state.
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("customer-1", 500)))
        .isInstanceOf(InvariantViolationException.class);

    assertThat(log.order())
        .containsExactly(
            "precheck:customer-standing", "precheck:warehouse-calendar", "handler");
    assertThat(orderCount()).isZero();
  }

  @Test
  void arefusalFromEachLayerRendersUnderItsOwnCodeOverHttp() {
    ResponseEntity<String> blocked =
        http.postForEntity(
            "/orders", Map.of("customerId", "blocked-1", "quantity", 5), String.class);
    ResponseEntity<String> overCap =
        http.postForEntity(
            "/orders", Map.of("customerId", "customer-1", "quantity", 500), String.class);

    // A precheck refusal and an invariant violation are both domain refusals, and the status follows
    // the ErrorCode's category rather than the layer that raised it: FORBIDDEN vs DOMAIN_RULE.
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(JsonPath.<String>read(blocked.getBody(), "$.code"))
        .isEqualTo("ordering.customer-blocked");
    assertThat(overCap.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(JsonPath.<String>read(overCap.getBody(), "$.code"))
        .isEqualTo("ordering.quantity-over-cap");
  }

  private long orderCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s19_order", Long.class);
  }
}

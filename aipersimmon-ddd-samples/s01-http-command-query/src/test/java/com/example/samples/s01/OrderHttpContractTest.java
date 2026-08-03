package com.example.samples.s01;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The outward contract, end to end over HTTP against a real PostgreSQL.
 *
 * <p>Each test names the mapping it pins down, because these are the assertions the rest of the
 * samples inherit rather than re-decide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class OrderHttpContractTest {

  @Autowired private TestRestTemplate http;

  @Test
  void placingAnOrderAnswersTheResourceItself() {
    ResponseEntity<String> response = place("customer-1", "SKU-1", 2);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = JsonPath.read(response.getBody(), "$.id");
    assertThat(response.getHeaders().getLocation()).hasToString("/orders/" + id);
    assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("PLACED");
    assertThat(JsonPath.<List<?>>read(response.getBody(), "$.lines")).hasSize(1);
    // No success envelope: the resource is at the root, not under a "data" member.
    assertThat(response.getBody()).doesNotContain("\"data\"");
    // Set by the framework's request-id filter, and echoed into every problem document.
    assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
  }

  @Test
  void readingBackAnOrderAnswersTheSameShape() {
    String id = JsonPath.read(place("customer-2", "SKU-2", 3).getBody(), "$.id");

    ResponseEntity<String> response = http.getForEntity("/orders/" + id, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<String>read(response.getBody(), "$.customerId")).isEqualTo("customer-2");
    assertThat(JsonPath.<Integer>read(response.getBody(), "$.lines[0].quantity")).isEqualTo(3);
  }

  @Test
  void anInvalidBodyIsRejectedBeforeAnyCommandIsSent() {
    ResponseEntity<String> response =
        http.postForEntity("/orders", Map.of("customerId", "", "lines", List.of()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    // Bean Validation failures render as about:blank with field errors — they do NOT reach the
    // /problems/validation-failed family, which only a coded VALIDATION error resolves to.
    assertThat(JsonPath.<String>read(response.getBody(), "$.type")).isEqualTo("about:blank");
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("Validation failed");
    assertThat(JsonPath.<List<?>>read(response.getBody(), "$.errors")).isNotEmpty();
  }

  @Test
  void confirmingTwiceIsTheContextsOwnProblemType() {
    String id = JsonPath.read(place("customer-3", "SKU-3", 1).getBody(), "$.id");
    assertThat(confirm(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<String> response = confirm(id);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // A ProblemCatalog override: this one error earns its own type and title.
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/order-not-confirmable");
    assertThat(JsonPath.<String>read(response.getBody(), "$.title"))
        .isEqualTo("Order cannot be confirmed");
    assertThat(JsonPath.<String>read(response.getBody(), "$.code"))
        .isEqualTo("ordering.order-not-confirmable");
  }

  @Test
  void anUnknownOrderRidesItsCategoryFamily() {
    ResponseEntity<String> response = http.getForEntity("/orders/nope", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    // No catalog entry for this code: the NOT_FOUND category's family type answers, and the code
    // member is what tells the client which not-found it was.
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/resource-not-found");
    assertThat(JsonPath.<String>read(response.getBody(), "$.title")).isEqualTo("Resource not found");
    assertThat(JsonPath.<String>read(response.getBody(), "$.code"))
        .isEqualTo("ordering.order-not-found");
    assertThat(JsonPath.<String>read(response.getBody(), "$.requestId")).isNotBlank();
  }

  private ResponseEntity<String> place(String customerId, String sku, int quantity) {
    return http.postForEntity(
        "/orders",
        Map.of("customerId", customerId, "lines", List.of(Map.of("sku", sku, "quantity", quantity))),
        String.class);
  }

  private ResponseEntity<String> confirm(String id) {
    return http.postForEntity("/orders/" + id + "/confirm", null, String.class);
  }
}

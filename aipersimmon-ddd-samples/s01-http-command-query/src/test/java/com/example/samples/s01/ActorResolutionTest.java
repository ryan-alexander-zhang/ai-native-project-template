package com.example.samples.s01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.operationlog.model.Actor;
import com.example.samples.s01.audit.ActorBindingFilter;
import com.example.samples.s01.audit.CurrentActor;
import com.example.samples.s01.ordering.application.PlaceOrder;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Where the actor comes from, and the failure that makes it worth asking.
 *
 * <p>S14's headline question. The resolver takes no arguments, so the answer cannot be "the command says
 * so"; it has to be a scope, and a scope has a lifetime. These tests are about that lifetime.
 */
class ActorResolutionTest extends AuditTestBase {

  @Test
  void anhttpRequestRecordsTheUserWhoMadeIt() {
    place("clerk-7", "Dana Clerk");

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("actor_type")).isEqualTo("USER");
    assertThat(row.get("actor_id")).isEqualTo("clerk-7");
    assertThat(row.get("actor_display")).isEqualTo("Dana Clerk");
  }

  /**
   * No request in scope means the service acted on its own, and the row says so as a distinct actor type.
   *
   * <p>{@code SYSTEM} rather than a user with an odd name, so "what did the service do by itself" stays a
   * query on {@code actor_type} instead of a string convention nobody documents. A scheduled sweep, an
   * outbox relay and a message consumer all land here.
   */
  @Test
  void acommandWithNoRequestInScopeRecordsTheServiceItself() {
    commandBus.send(new PlaceOrder("customer-1", lines()));

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
    assertThat(row.get("actor_id")).isEqualTo("s01-http-command-query");
    // Not null: Actor.system(id) sets the display name to the id, because a system actor has no human
    // name to be missing. Which means a rendered audit trail never has a blank "by" column — an empty
    // display would be indistinguishable from a user whose name failed to resolve.
    assertThat(row.get("actor_display")).isEqualTo("s01-http-command-query");
  }

  /**
   * The failure: a binding that outlives its request files somebody else's work under a real person.
   *
   * <p>This is what a missing {@code finally} looks like from the audit table. The binding is established
   * and not cleared — exactly the state a pooled request thread is left in — and then a command runs that
   * never went near HTTP, the way a scheduled task would. The row names a user who did nothing.
   *
   * <p>Note that nothing fails, nothing logs, and the row is well-formed. The only way to see this is to
   * ask the table who it thinks acted, which is why it is a test rather than a code-review note.
   */
  @Test
  void abindingThatWasNeverClearedIsAttributedToTheWrongActor() {
    CurrentActor.bind(Actor.user("clerk-7", "Dana Clerk"));

    commandBus.send(new PlaceOrder("customer-1", lines()));

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("actor_type")).isEqualTo("USER");
    assertThat(row.get("actor_id")).isEqualTo("clerk-7");
  }

  /**
   * And the control: the filter leaves nothing bound on the thread it ran on.
   *
   * <p>Without this the test above is not a finding — it would only show that a hand-set binding is honoured,
   * which is the mechanism working. What makes it a finding is that the filter's {@code finally} is the sole
   * reason production does not look like it.
   *
   * <p><strong>Driven through the filter directly, on this thread, and that detail is the point.</strong> The
   * obvious version of this test — make an HTTP call, then dispatch a command and check the actor — passes
   * whether or not the filter clears anything, because {@code TestRestTemplate} runs the request on a
   * container worker thread while the command runs on the test's. A thread-local leak is invisible to any
   * test that does not stay on one thread. Measured: with the {@code finally} deleted, the HTTP version of
   * this test stayed green and this one goes red.
   */
  @Test
  void thefilterLeavesNothingBoundOnTheThreadItRanOn() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    request.addHeader(ActorBindingFilter.ACTOR_HEADER, "clerk-7");
    request.addHeader(ActorBindingFilter.ACTOR_NAME_HEADER, "Dana Clerk");
    AtomicReference<Actor> seenInsideTheChain = new AtomicReference<>();

    new ActorBindingFilter()
        .doFilterInternal(
            request,
            new MockHttpServletResponse(),
            (req, res) -> seenInsideTheChain.set(CurrentActor.current().orElse(null)));

    // Bound while the request was being served...
    assertThat(seenInsideTheChain.get()).isEqualTo(Actor.user("clerk-7", "Dana Clerk"));
    // ...and gone the moment it finished, on this same thread.
    assertThat(CurrentActor.current()).isEmpty();
  }

  /** And it unbinds even when the request blows up, which is the case a {@code finally} exists for. */
  @Test
  void thefilterUnbindsEvenWhenTheRequestFails() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
    request.addHeader(ActorBindingFilter.ACTOR_HEADER, "clerk-7");

    assertThatThrownBy(
            () ->
                new ActorBindingFilter()
                    .doFilterInternal(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> {
                          throw new IllegalStateException("handler blew up");
                        }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(CurrentActor.current()).isEmpty();
  }

  /**
   * The actor is never taken from the command, however plausible the field looks.
   *
   * <p>{@code PlaceOrder} carries a {@code customerId}, which is a person, and it is not the actor: a clerk
   * placing an order on a customer's behalf is the ordinary case, and an audit trail that conflated them
   * would attribute the clerk's mistakes to the customer. The resolver's no-argument signature is what makes
   * this impossible to get wrong by accident — there is nothing to pass it.
   */
  @Test
  void thecommandsOwnPersonFieldIsNotTheActor() {
    place("clerk-7", "Dana Clerk");

    Map<String, Object> row = onlyAuditRow();
    assertThat(row.get("actor_id")).isEqualTo("clerk-7");
    assertThat((String) row.get("summary")).contains("customer-1");
    assertThat(row.get("actor_id")).isNotEqualTo("customer-1");
  }

  private void place(String actorId, String actorName) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Actor", actorId);
    headers.set("X-Actor-Name", actorName);
    Map<String, Object> body =
        Map.of("customerId", "customer-1", "lines", List.of(Map.of("sku", "SKU-1", "quantity", 2)));
    String response =
        http.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
            .getBody();
    JsonPath.read(response, "$.id");
  }

  private static List<PlaceOrder.Line> lines() {
    return List.of(new PlaceOrder.Line("SKU-1", 2));
  }
}

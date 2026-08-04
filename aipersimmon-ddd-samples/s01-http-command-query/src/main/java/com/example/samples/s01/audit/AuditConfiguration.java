package com.example.samples.s01.audit;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.model.Actor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one bean the operation log will not start without, and the decision it encodes.
 *
 * <p>Nothing bound means <strong>this service acted on its own</strong> — a scheduled sweep, an outbox
 * relay, a message consumer, a startup task. That is a real answer and it has to be given here, because
 * the alternatives are all worse:
 *
 * <ul>
 *   <li><strong>Throw.</strong> Every non-HTTP entry point becomes unable to run any audited command,
 *       and the audit log's availability becomes the application's availability.
 *   <li><strong>Return an anonymous or empty actor.</strong> The row records that nobody did it, which
 *       is indistinguishable from a bug in the binding and unactionable in either case.
 *   <li><strong>Fall back to the last known actor.</strong> The failure this sample spends a test on:
 *       silently correct-looking, and wrong in the way that matters most.
 * </ul>
 *
 * <p>So: a named system actor. {@code Actor.system(...)} is a distinct actor <em>type</em>, not a user
 * with a funny name, so "which operations were performed by the service itself" stays a query rather
 * than a string convention — and an audit review can tell "the scheduler closed this order" from "a
 * person did, and we lost their name".
 *
 * <p>The resolver does no I/O and holds no state, as its contract requires: each interceptor calls it to
 * freeze its own snapshot, on the success and failure paths separately, and a resolver that queried
 * anything would put a database round trip on both.
 */
@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

  /**
   * The service's own identity when no request is in scope. A constant, and deliberately the
   * application's name: an audit row saying {@code s01-http-command-query} is traceable to a deployment,
   * whereas {@code system} is traceable to nothing once a second service shares the table.
   */
  static final String SELF = "s01-http-command-query";

  @Bean
  OperationActorResolver operationActorResolver() {
    return () -> CurrentActor.current().orElseGet(() -> Actor.system(SELF));
  }
}

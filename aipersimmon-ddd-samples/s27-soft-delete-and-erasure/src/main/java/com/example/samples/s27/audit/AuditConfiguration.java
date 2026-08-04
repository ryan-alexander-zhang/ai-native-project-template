package com.example.samples.s27.audit;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.model.Actor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A fixed operator identity, and the reason that is acceptable here rather than a shortcut.
 *
 * <p>S14 is about where an actor comes from and shows the whole arrangement — a binding at the HTTP boundary, a
 * cleared thread-local, a system fallback. Repeating it here would be repeating a solved scenario; what S27 needs
 * from the audit log is different: <strong>what a row contains, and whether it survives an erasure.</strong> So
 * this returns one service actor and the tests never assert on the actor at all.
 *
 * <p>Said plainly because the alternative is a reader assuming an erasure is performed by nobody in particular.
 * In a real service the actor on a {@code customer.erase} row is the most closely read field on it: the point of
 * auditing a compliance operation is to be able to say who discharged the obligation, and "the service" is not an
 * answer a regulator accepts.
 */
@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

  @Bean
  OperationActorResolver operationActorResolver() {
    return () -> Actor.service("s27-compliance-tooling");
  }
}

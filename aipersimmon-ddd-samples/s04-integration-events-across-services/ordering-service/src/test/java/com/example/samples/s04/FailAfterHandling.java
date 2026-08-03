package com.example.samples.s04;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.example.samples.s04.ordering.application.PlaceOrder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Fails a command <em>after</em> its handler has run and before the transaction commits.
 *
 * <p>That is the only position from which the atomicity claim can be tested: the aggregate has been
 * saved and the outbox row inserted, and the question is whether both survive. Order 250 puts it inside
 * the transaction interceptor (200), and the throw happens after {@code proceed()} returns.
 */
@TestConfiguration(proxyBeanMethods = false)
class FailAfterHandling {

  static final String POISON_CUSTOMER = "customer-that-fails-late";

  @Bean
  @Order(250)
  CommandInterceptor failAfterHandling() {
    return new CommandInterceptor() {
      @Override
      public <R> R intercept(
          Command<R> command, CommandContext context, Invocation<R> invocation) {
        R result = invocation.proceed();
        if (command instanceof PlaceOrder place
            && POISON_CUSTOMER.equals(place.customerId())) {
          throw new IllegalStateException("something fails after the handler returned");
        }
        return result;
      }

      @Override
      public int order() {
        return 250;
      }
    };
  }
}

package com.example.samples.s05;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * Makes the database fail once, for one sku, so the retryable branch of the listener's classification is
 * exercised by a real failure rather than by reading the code.
 *
 * <p>Order 300 puts it inside the transaction interceptor (200), so the failure rolls the transaction
 * back exactly as a real one would — which is the property under test: the record is redelivered, and the
 * second attempt starts from unchanged state.
 */
@TestConfiguration(proxyBeanMethods = false)
class FailOnceForSku {

  /** The sku whose first attempt fails. */
  static final String POISON_SKU = "SKU-TRANSIENT";

  static final AtomicInteger attempts = new AtomicInteger();

  @Bean
  @Order(300)
  CommandInterceptor failOnceForTransientSku() {
    return new CommandInterceptor() {
      @Override
      public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
        if (command.toString().contains(POISON_SKU) && attempts.incrementAndGet() == 1) {
          throw new TransientDataAccessResourceException("the database is briefly unavailable");
        }
        return invocation.proceed();
      }

      @Override
      public int order() {
        return 300;
      }
    };
  }
}

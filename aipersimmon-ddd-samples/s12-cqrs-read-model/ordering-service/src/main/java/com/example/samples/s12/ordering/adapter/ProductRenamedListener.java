package com.example.samples.s12.ordering.adapter;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s12.ordering.api.ProductRenamed;
import com.example.samples.s12.ordering.application.RecordProductName;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The catalogue said a name changed; this turns that into a command and stops.
 *
 * <p>It holds no rule and it does not deduplicate: the framework's inbox has already rejected a redelivery
 * before this method was called, which is why {@code inbox} is listed under the flyway components. Worth
 * knowing what the inbox is and is not protecting here — the projection's rebuild is idempotent by
 * construction, so a duplicate would produce the same row anyway. What the inbox saves is the <em>work</em>:
 * a redelivered rename of a popular sku would otherwise recompute every affected list row again.
 *
 * <p>It listens to the envelope rather than the payload, so the causal ids survive the hop and the resulting
 * projection writes stay traceable to whoever renamed the product two services ago.
 */
@Component
class ProductRenamedListener {

  private final CommandBus commandBus;

  ProductRenamedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  void on(EventEnvelope<ProductRenamed> envelope) {
    ProductRenamed event = envelope.payload();
    commandBus.send(
        new RecordProductName(event.sku(), event.name()), InboundEvents.commandContext(envelope));
  }
}

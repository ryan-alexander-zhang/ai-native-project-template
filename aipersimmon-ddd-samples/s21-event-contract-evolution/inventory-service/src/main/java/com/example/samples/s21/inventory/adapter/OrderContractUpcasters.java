package com.example.samples.s21.inventory.adapter;

import com.aipersimmon.ddd.integration.EventUpcaster;
import com.example.samples.s21.inventory.api.OrderLine;
import com.example.samples.s21.inventory.api.OrderPlaced;
import com.example.samples.s21.inventory.api.OrderPlacedV1;
import com.example.samples.s21.inventory.api.OrderPlacedV2;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The v1 → v2 → v3 chain: two hops, registered once, so the rest of the service only ever sees v3.
 *
 * <p><strong>What this buys.</strong> Without upcasters, coexistence means one listener method per
 * historical revision, in every consumer, forever — and the count only grows, because a published
 * contract outlives the code that reads it. The chain moves that cost to the boundary: one upcaster
 * per retired revision, and the application below is written once, against the current shape.
 *
 * <p><strong>The iron law.</strong> An upcaster is a total function of the payload — no I/O, no
 * lookup, no failure path for well-formed input — and <em>what the old revision never carried, the
 * upcast must not invent</em>. {@link #orderPlacedV2ToV3} leaves {@code warehouseCode} null rather
 * than substituting the obvious default, because a substituted value arrives downstream
 * indistinguishable from one the publisher actually sent. The default belongs where it can be seen and
 * changed: in {@code OrderPlacedListener}, which is this consumer's rule, not the contract's data.
 *
 * <p><strong>What the library verifies at startup</strong>, from the two type parameters' own
 * {@code @EventType} contracts, so a mis-declared upcaster fails the deployment by name instead of the
 * first old record: both revisions carry the same logical name, the version strictly increases (which
 * is also what makes every chain finite), no two upcasters claim the same source revision, and neither
 * type parameter is erased — hence concrete classes and anonymous-class style rather than lambdas,
 * since a lambda erases the generic supertype the chain reads.
 *
 * <p><strong>What upcasting does not loosen.</strong> An unregistered revision is still dead-lettered:
 * normalisation is for revisions this service has adopted, not a licence to guess at ones it has not.
 *
 * <p><strong>The profile.</strong> {@code @Profile("!upcasters-removed")} exists for exactly one
 * negative control, and the fact it proves is the most dangerous one in this sample: with the retired
 * <em>class</em> deleted an old record is dead-lettered (loud), but with only the <em>upcaster</em>
 * deleted the record resolves to a class no handler is typed for and is skipped in silence. Two ways to
 * retire a revision, one loud and one silent, and the silent one looks tidier. See
 * {@code SilentSkipWhenTheUpcasterIsGoneTest}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!upcasters-removed")
class OrderContractUpcasters {

  /**
   * v1 → v2: one line becomes a list of one. Exact, and losslessly so — nothing is guessed, because
   * v1 held everything v2's shape needs.
   */
  @Bean
  EventUpcaster<OrderPlacedV1, OrderPlacedV2> orderPlacedV1ToV2() {
    return new EventUpcaster<>() {
      @Override
      public OrderPlacedV2 upcast(OrderPlacedV1 v1) {
        return new OrderPlacedV2(v1.orderId(), List.of(new OrderLine(v1.sku(), v1.quantity())));
      }
    };
  }

  /**
   * v2 → v3: the addition stays absent.
   *
   * <p>Writing {@code "MAIN"} here instead of null would be the whole failure mode of contract
   * evolution in one line — every old order would arrive claiming a warehouse the customer never
   * named, and nothing downstream could tell those apart from the real ones. Absence is information;
   * the upcast's job is to preserve it, not to smooth it over.
   */
  @Bean
  EventUpcaster<OrderPlacedV2, OrderPlaced> orderPlacedV2ToV3() {
    return new EventUpcaster<>() {
      @Override
      public OrderPlaced upcast(OrderPlacedV2 v2) {
        return new OrderPlaced(v2.orderId(), v2.lines(), null);
      }
    };
  }
}

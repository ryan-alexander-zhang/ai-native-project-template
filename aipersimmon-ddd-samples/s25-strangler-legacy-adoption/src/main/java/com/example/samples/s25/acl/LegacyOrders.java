package com.example.samples.s25.acl;

import com.example.samples.s25.legacy.LegacyOrderRecord;
import com.example.samples.s25.legacy.LegacyOrderService;
import com.example.samples.s25.refunds.application.OrderFacts;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;

/**
 * The anti-corruption layer, and it is <strong>one class</strong>. That is the whole design.
 *
 * <p>The catalogue asks how legacy code gets wrapped rather than called from everywhere, and the useful answer is not a
 * pattern name — it is a number. This is the only class in the service that may touch the {@code legacy} package, and an
 * ArchUnit rule says so ({@code ArchitectureTest.onlyTheAclTouchesTheLegacy}). Everything else about an ACL follows from
 * that constraint; nothing follows from calling it an ACL.
 *
 * <p><strong>Three translations happen here, and each is a legacy fact stopping at the door:</strong>
 *
 * <ul>
 *   <li>a status string with four values becomes the one boolean the aggregate reasons about. {@code "CANCELLED"} is a
 *       string comparison, and it is a string comparison <em>here</em> rather than in six places;
 *   <li>{@code EmptyResultDataAccessException} — a Spring JDBC exception, thrown because the monolith uses
 *       {@code queryForObject} — becomes {@code Optional.empty()}. Letting it out would make every caller of the new
 *       context catch a persistence exception from a database it does not know it is talking to;
 *   <li>{@code LegacyOrderRecord} does not appear in the return type. The port's {@code Snapshot} does.
 * </ul>
 *
 * <p>What this class is <em>not</em>: it is not a repository, it does not cache, and it does not decide anything. An ACL
 * that starts making decisions has become a second model of the same thing, and then there are three.
 */
@Component
class LegacyOrders implements OrderFacts {

  private final LegacyOrderService legacy;

  LegacyOrders(LegacyOrderService legacy) {
    this.legacy = legacy;
  }

  @Override
  public Optional<Snapshot> of(long orderId) {
    LegacyOrderRecord record;
    try {
      record = legacy.findOrder(orderId);
    } catch (EmptyResultDataAccessException noSuchOrder) {
      // The monolith's way of saying "not found" is an exception from the JDBC layer. It stops here.
      return Optional.empty();
    }
    return Optional.of(new Snapshot("CANCELLED".equals(record.status()), record.totalCents()));
  }
}

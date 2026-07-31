package com.aipersimmon.ddd.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.event.DomainEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AbstractAggregateRootTest {

  private record SampleEvent(String what) implements DomainEvent {}

  /**
   * One identity type shared by both fixture roots, so cross-type equality is really cross-type.
   */
  private record Ref(String value) implements Identifier {}

  /** A minimal aggregate root that exposes the protected event-recording hook for testing. */
  private static final class Order extends AbstractAggregateRoot<Ref> {
    private final Ref id;

    Order(String id) {
      this.id = id == null ? null : new Ref(id);
    }

    /** Stands in for a repository rehydrating a persisted root at a known version. */
    static Order reconstitute(String id, long version) {
      Order order = new Order(id);
      order.restoreVersion(version);
      return order;
    }

    @Override
    public Ref id() {
      return id;
    }

    void raise(DomainEvent event) {
      registerEvent(event);
    }
  }

  /** A second root type sharing Order's identity type, to pin down cross-type inequality. */
  private static final class Shipment extends AbstractAggregateRoot<Ref> {
    private final Ref id;

    Shipment(String id) {
      this.id = new Ref(id);
    }

    @Override
    public Ref id() {
      return id;
    }
  }

  @Test
  void startsWithNoEvents() {
    assertTrue(new Order("o-1").domainEvents().isEmpty());
  }

  @Test
  void registerEvent_recordsEventsInOrder() {
    Order order = new Order("o-1");
    SampleEvent first = new SampleEvent("a");
    SampleEvent second = new SampleEvent("b");

    order.raise(first);
    order.raise(second);

    assertEquals(List.of(first, second), order.domainEvents());
  }

  @Test
  void domainEvents_returnsAnUnmodifiableSnapshot() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));

    List<DomainEvent> events = order.domainEvents();

    assertThrows(UnsupportedOperationException.class, () -> events.add(new SampleEvent("x")));
  }

  @Test
  void domainEvents_isASnapshotAndNotAViewOfTheLiveList() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));
    List<DomainEvent> taken = order.domainEvents();

    order.raise(new SampleEvent("b"));

    // The assertion the sibling test above never made: it checked only that the returned list
    // refuses writes, which an unmodifiable VIEW of the live list does too. That is what this
    // method used to return, and iterating it while a listener recorded another event on the same
    // aggregate threw ConcurrentModificationException from inside the publisher.
    assertEquals(1, taken.size(), "the list handed out earlier must not have grown");
    assertEquals(2, order.domainEvents().size());
  }

  @Test
  void drainDomainEvents_returnsTheEventsAndLeavesTheAggregateEmpty() {
    Order order = new Order("o-1");
    SampleEvent first = new SampleEvent("a");
    SampleEvent second = new SampleEvent("b");
    order.raise(first);
    order.raise(second);

    List<DomainEvent> drained = order.drainDomainEvents();

    assertEquals(List.of(first, second), drained, "in the order they were recorded");
    assertTrue(order.domainEvents().isEmpty());
  }

  @Test
  void drainDomainEvents_keepsWhatWasRecordedAfterIt() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));
    order.drainDomainEvents();

    order.raise(new SampleEvent("b"));

    // Draining and then clearing would be two steps, and an event recorded between them would be
    // discarded. Taking and emptying in one step is what makes the later event survivable.
    assertEquals(1, order.domainEvents().size());
  }

  @Test
  void drainDomainEvents_onAnAggregateWithNoEventsIsEmptyRatherThanAnError() {
    assertTrue(new Order("o-1").drainDomainEvents().isEmpty());
  }

  @Test
  void clearDomainEvents_removesEveryRecordedEvent() {
    Order order = new Order("o-1");
    order.raise(new SampleEvent("a"));
    order.raise(new SampleEvent("b"));

    order.clearDomainEvents();

    assertTrue(order.domainEvents().isEmpty());
  }

  // ----- optimistic-lock version (issue-00051) -----

  @Test
  void newAggregate_startsAtVersionZero() {
    assertEquals(0L, new Order("o-1").version());
  }

  @Test
  void restoreVersion_carriesThePersistedVersion() {
    assertEquals(7L, Order.reconstitute("o-1", 7L).version());
  }

  @Test
  void versionAdvanced_incrementsByOne() {
    Order order = Order.reconstitute("o-1", 3L);

    order.versionAdvanced();

    assertEquals(4L, order.version());
  }

  @Test
  void versionAdvanced_onANewAggregate_movesItToOne() {
    Order order = new Order("o-1");

    order.versionAdvanced();

    assertEquals(1L, order.version());
  }

  @Test
  void restoreVersion_rejectsANegativeVersion() {
    assertThrows(IllegalArgumentException.class, () -> Order.reconstitute("o-1", -1L));
  }

  // ----- identity equality (issue-00055) -----

  @Test
  void anAggregateEqualsItself() {
    Order order = new Order("o-1");

    assertEquals(order, order);
  }

  @Test
  void twoInstancesOfTheSameIdentity_areEqual() {
    Order loadedOnce = new Order("o-1");
    Order loadedTwice = new Order("o-1");

    assertEquals(loadedOnce, loadedTwice);
    assertEquals(loadedOnce.hashCode(), loadedTwice.hashCode());
  }

  @Test
  void aSetDeduplicatesTheSameAggregateLoadedTwice() {
    // A mutable set, not Set.of: the immutable factory rejects duplicates instead of collapsing
    // them, which would pass for the wrong reason.
    Set<Order> set = new HashSet<>();
    set.add(new Order("o-1"));
    set.add(new Order("o-1"));

    assertEquals(1, set.size());
  }

  @Test
  void differentIdentities_areNotEqual() {
    assertNotEquals(new Order("o-1"), new Order("o-2"));
  }

  @Test
  void differentAggregateTypesSharingAnIdentity_areNotEqual() {
    assertNotEquals(new Order("o-1"), new Shipment("o-1"));
    assertNotEquals(new Shipment("o-1"), new Order("o-1"));
  }

  /**
   * Two freshly created aggregates that have not been assigned an identity yet are two different
   * things-in-progress, not one: {@code Objects.equals(null, null)} is true, so without an explicit
   * fallback they compared equal — and a {@code Set} used to collect new aggregates before their
   * ids are minted would silently collapse them.
   */
  @Test
  void twoNewAggregatesWithoutAnIdentityYet_areNotEqual() {
    assertNotEquals(new Order(null), new Order(null));
  }

  @Test
  void anUnidentifiedAggregateStillEqualsItself() {
    Order order = new Order(null);

    assertEquals(order, order);
  }

  @Test
  void anAggregateIsNotEqualToNullOrAForeignType() {
    Order order = new Order("o-1");

    assertNotEquals(null, order);
    assertNotEquals("o-1", order);
  }

  @Test
  void versionAndRecordedEventsDoNotAffectEquality() {
    Order pristine = Order.reconstitute("o-1", 3L);
    Order advanced = Order.reconstitute("o-1", 9L);
    advanced.raise(new SampleEvent("a"));

    assertEquals(pristine, advanced);
    assertEquals(pristine.hashCode(), advanced.hashCode());
  }
}

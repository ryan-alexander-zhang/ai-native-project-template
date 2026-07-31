package com.aipersimmon.ddd.core.model;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for aggregate roots that record domain events while executing behaviour. During a use
 * case the root registers events via {@link #registerEvent(DomainEvent)}; after the aggregate is
 * persisted the application takes them with {@link #drainDomainEvents()} and publishes them.
 *
 * <p>Framework-free: it records events in memory and takes no stance on how they are published.
 * Subclasses supply the aggregate's identity via {@link #id()}.
 *
 * <p>It also carries the {@linkplain #version() optimistic-lock version} that makes the aggregate a
 * real transactional consistency unit: without it, two commands that each pass the root's own state
 * guards can both write, and the later write silently discards the earlier one.
 *
 * <p>Equality is by identity, which is what makes this an entity rather than a value: {@link
 * #equals(Object)} and {@link #hashCode()} are {@code final} here so the contract cannot drift per
 * subclass. Neither the version nor the recorded events take part — they are persistence and
 * lifecycle state, not identity.
 *
 * <p>This class <em>is</em> the aggregate-root contract; there is no separate marker interface to
 * implement as well. The role is declared with {@link
 * com.aipersimmon.ddd.core.annotation.AggregateRoot @AggregateRoot}, which is the one vocabulary
 * that covers every building-block role, and extending this supplies the behaviour.
 *
 * @param <ID> the identity type of the root — a dedicated {@link Identifier} value object, never a
 *     raw {@code String} or {@code UUID}. The bound is what makes {@code Identifier}'s promise
 *     ("keeps the identities of different aggregates from being mixed up") true by construction
 *     rather than by convention: an aggregate simply cannot be declared over a bare primitive.
 */
public abstract class AbstractAggregateRoot<ID extends Identifier> {

  /**
   * The aggregate's identity. Declared here rather than inherited from a marker interface, so that
   * a root has one supertype and one place to look.
   */
  public abstract ID id();

  private final transient List<DomainEvent> domainEvents = new ArrayList<>();

  private long version;

  /** Record a domain event to be published after the aggregate is persisted. */
  protected void registerEvent(DomainEvent event) {
    domainEvents.add(event);
  }

  /**
   * Enforce a business invariant from inside an intention-revealing method: throw an {@link
   * InvariantViolationException} if {@code invariant} is broken, otherwise do nothing. Prefer this
   * over inline {@code if (...) throw} when the invariant is worth naming and reusing; trivial
   * one-off guards stay as coded {@code throw}.
   */
  protected void checkInvariant(Invariant invariant) {
    if (invariant.isBroken()) {
      throw new InvariantViolationException(invariant);
    }
  }

  /**
   * An unmodifiable snapshot of the events recorded since load or creation.
   *
   * <p>A snapshot, and not a view of the live list, which is what this used to return while
   * promising otherwise. The difference shows up in one situation and it is not a rare one: a
   * synchronous listener, invoked while these events are being published, records another event on
   * the same aggregate — and iteration over a view then dies with {@link
   * java.util.ConcurrentModificationException} from inside the publisher, far from the listener
   * that caused it.
   */
  public List<DomainEvent> domainEvents() {
    return List.copyOf(domainEvents);
  }

  /**
   * Take the recorded events and clear them, in one step.
   *
   * <p>This exists because copying and clearing as two steps is not safe in the situation above.
   * Publishing a snapshot and then clearing everything would discard an event a listener recorded
   * during publication — trading a loud {@code ConcurrentModificationException} for a silently
   * dropped domain event, which is a worse answer to the same problem. Draining first means
   * anything recorded afterwards is still on the aggregate, where it can be seen.
   *
   * @return the events that were recorded, in order
   */
  public List<DomainEvent> drainDomainEvents() {
    List<DomainEvent> drained = List.copyOf(domainEvents);
    domainEvents.clear();
    return drained;
  }

  /**
   * Clear the recorded events without publishing them.
   *
   * <p>Prefer {@link #drainDomainEvents()} when the events are about to be published; this is for
   * discarding them deliberately, such as rebuilding an aggregate in a test fixture.
   */
  public void clearDomainEvents() {
    domainEvents.clear();
  }

  /**
   * The version this aggregate was loaded at, or {@code 0} for one that has not been persisted yet.
   * A repository puts it in the {@code WHERE} clause of the update so a write that lost a
   * concurrent race affects no row, and distinguishes an insert ({@code 0}) from an update without
   * a preceding existence query.
   */
  public final long version() {
    return version;
  }

  /**
   * Adopt the version a repository just loaded. Called from the aggregate's own rehydrating
   * factory, so a repository cannot inject a version behind the aggregate's back.
   *
   * @throws IllegalArgumentException if {@code persistedVersion} is negative
   */
  protected final void restoreVersion(long persistedVersion) {
    if (persistedVersion < 0) {
      throw new IllegalArgumentException("version must not be negative: " + persistedVersion);
    }
    this.version = persistedVersion;
  }

  /**
   * Record that this aggregate's row now holds the next version; call after a version-checked write
   * succeeded, so saving the same instance again in one transaction checks against the new value
   * rather than the stale one.
   *
   * <p><strong>For persistence adapters only.</strong> It is public because the repository bases
   * live in other packages, but a domain or application class calling it would advance the witness
   * without a write and disarm the optimistic lock — the architecture rule {@code
   * versionWitnessIsAdvancedOnlyByPersistenceAdapters} refuses the call at build time.
   */
  public final void versionAdvanced() {
    version++;
  }

  /**
   * Equal when the other object is an aggregate of exactly the same class with an equal {@link
   * #id()}. The class must match exactly rather than by {@code instanceof}: letting a subclass
   * equal its parent would break symmetry, and two different aggregate types are different entities
   * even where their identity values coincide.
   *
   * <p>An aggregate whose identity is still {@code null} — created but not yet assigned an id — is
   * equal only to itself. {@code Objects.equals(null, null)} is true, so without this fallback two
   * distinct things-in-progress compared equal and a {@code Set} would silently collapse them.
   */
  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    if (id() == null) {
      return false;
    }
    return Objects.equals(id(), ((AbstractAggregateRoot<?>) other).id());
  }

  @Override
  public final int hashCode() {
    return Objects.hashCode(id());
  }
}

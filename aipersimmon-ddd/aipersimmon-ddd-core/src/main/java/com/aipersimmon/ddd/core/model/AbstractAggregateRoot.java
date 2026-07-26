package com.aipersimmon.ddd.core.model;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base class for aggregate roots that record domain events while executing behaviour. During a use
 * case the root registers events via {@link #registerEvent(DomainEvent)}; after the aggregate is
 * persisted the application drains {@link #domainEvents()}, publishes them, and then calls {@link
 * #clearDomainEvents()}.
 *
 * <p>Framework-free: it records events in memory and takes no stance on how they are published.
 * Subclasses supply the aggregate's identity via {@link #id()}.
 *
 * <p>It also carries the {@linkplain #version() optimistic-lock version} that makes the aggregate a
 * real transactional consistency unit: without it, two commands that each pass the root's own state
 * guards can both write, and the later write silently discards the earlier one. See {@code
 * design-00011}.
 *
 * <p>Equality is by identity, as {@link Entity} requires: {@link #equals(Object)} and {@link
 * #hashCode()} are {@code final} here so the contract cannot drift per subclass. Neither the
 * version nor the recorded events take part — they are persistence and lifecycle state, not
 * identity.
 *
 * @param <ID> the identity type of the root
 */
public abstract class AbstractAggregateRoot<ID> implements AggregateRoot<ID> {

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

  /** An unmodifiable snapshot of the events recorded since load or creation. */
  public List<DomainEvent> domainEvents() {
    return Collections.unmodifiableList(domainEvents);
  }

  /** Clear the recorded events; call after they have been published. */
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
   */
  public final void versionAdvanced() {
    version++;
  }

  /**
   * Equal when the other object is an aggregate of exactly the same class with an equal {@link
   * #id()}. The class must match exactly rather than by {@code instanceof}: letting a subclass
   * equal its parent would break symmetry, and two different aggregate types are different entities
   * even where their identity values coincide.
   */
  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    return Objects.equals(id(), ((AbstractAggregateRoot<?>) other).id());
  }

  @Override
  public final int hashCode() {
    return Objects.hashCode(id());
  }
}

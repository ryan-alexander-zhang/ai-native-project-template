package com.example.samples.s27.customer.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.Optional;

/**
 * A customer profile: some personal data, a lifecycle, and one irreversible operation.
 *
 * <p>{@link #close(String)} is the soft delete that <em>is</em> domain state — reversible by
 * {@link #reopen()}, explained by its reason, and a legitimate thing to list. {@link #erase(Instant)} is the
 * compliance erasure, and everything awkward about it is visible in this class:
 *
 * <ul>
 *   <li>It is <strong>not</strong> a deletion. The aggregate still exists afterwards, still has an identity,
 *       and still has to satisfy every invariant — which is why the tombstones have to be values the model
 *       accepts rather than nulls poked in behind its back.
 *   <li>It is <strong>irreversible by construction</strong>: there is no {@code unerase}. Anything that could
 *       put the data back would mean the data was still somewhere.
 *   <li>It is <strong>idempotent</strong>, and has to be: an erasure request arrives more than once (a retry,
 *       a second regulator letter, a replayed message), and the second one must not fail and must not
 *       overwrite the tombstone with a fresh one — the erasure date is evidence.
 * </ul>
 */
@AggregateRoot
public final class Customer extends AbstractAggregateRoot<CustomerId> {

  private final CustomerId id;
  private EmailAddress email;
  private String displayName;
  private String phone;
  private CustomerStatus status;
  private String closedReason;
  private Instant erasedAt;

  private Customer(
      CustomerId id,
      EmailAddress email,
      String displayName,
      String phone,
      CustomerStatus status,
      String closedReason,
      Instant erasedAt) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.phone = phone;
    this.status = status;
    this.closedReason = closedReason;
    this.erasedAt = erasedAt;
  }

  public static Customer register(
      CustomerId id, EmailAddress email, String displayName, String phone) {
    return new Customer(
        id, email, requireName(displayName), phone, CustomerStatus.ACTIVE, null, null);
  }

  public static Customer reconstitute(
      CustomerId id,
      EmailAddress email,
      String displayName,
      String phone,
      CustomerStatus status,
      String closedReason,
      Instant erasedAt,
      long version) {
    Customer customer =
        new Customer(id, email, displayName, phone, status, closedReason, erasedAt);
    customer.restoreVersion(version);
    return customer;
  }

  /**
   * Change the address.
   *
   * @return false when it is already that, so an idempotent retry announces nothing
   * @throws IllegalStateException if the profile has been erased
   */
  public boolean changeEmailTo(EmailAddress newEmail) {
    requireNotErased("change the email of");
    if (newEmail.equals(email)) {
      return false;
    }
    this.email = newEmail;
    return true;
  }

  /**
   * The domain deletion. Reversible, explained, and queryable.
   *
   * @return false when already closed
   */
  public boolean close(String reason) {
    requireNotErased("close");
    if (status == CustomerStatus.CLOSED) {
      return false;
    }
    this.status = CustomerStatus.CLOSED;
    this.closedReason = reason;
    return true;
  }

  /**
   * And back again, which is the property a flag cannot offer.
   *
   * @return false when already active
   * @throws IllegalStateException if the profile has been erased — there is nothing left to reopen
   */
  public boolean reopen() {
    requireNotErased("reopen");
    if (status == CustomerStatus.ACTIVE) {
      return false;
    }
    this.status = CustomerStatus.ACTIVE;
    this.closedReason = null;
    return true;
  }

  /**
   * Overwrite the personal data, keep the record.
   *
   * <p>The tombstones are chosen so the result is still a valid customer: an email in the reserved
   * {@code .invalid} domain and <strong>keyed by this customer's id</strong>, a placeholder name, and no
   * phone. The id in the address is not decoration — the email column is uniquely indexed, so a constant
   * tombstone would make the <em>second</em> erasure a duplicate-key failure.
   * {@code UniqueEmailTest.thetombstoneHasToBeUniquePerCustomer} measures that.
   *
   * <p>The profile is also closed, because an erased profile cannot be used, and closing it is how every
   * rule that reads {@code status} finds that out without learning about erasure.
   *
   * @return false when already erased, so a repeated request is a no-op that keeps the original date
   */
  public boolean erase(Instant at) {
    if (erasedAt != null) {
      return false;
    }
    this.email = new EmailAddress("erased+" + id.value() + "@invalid");
    this.displayName = "(erased)";
    this.phone = null;
    this.status = CustomerStatus.CLOSED;
    this.closedReason = "erased";
    this.erasedAt = at;
    return true;
  }

  private void requireNotErased(String what) {
    if (erasedAt != null) {
      throw new IllegalStateException(
          "cannot "
              + what
              + " customer "
              + id.value()
              + ": the profile was erased at "
              + erasedAt
              + ". An erasure is not a state to be worked around — if this call came from a replayed"
              + " message or a retried job, the right outcome is that it does nothing.");
    }
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("display name must not be blank");
    }
    return name.strip();
  }

  @Override
  public CustomerId id() {
    return id;
  }

  public EmailAddress email() {
    return email;
  }

  public String displayName() {
    return displayName;
  }

  public Optional<String> phone() {
    return Optional.ofNullable(phone);
  }

  public CustomerStatus status() {
    return status;
  }

  public Optional<String> closedReason() {
    return Optional.ofNullable(closedReason);
  }

  public Optional<Instant> erasedAt() {
    return Optional.ofNullable(erasedAt);
  }

  public boolean isErased() {
    return erasedAt != null;
  }
}

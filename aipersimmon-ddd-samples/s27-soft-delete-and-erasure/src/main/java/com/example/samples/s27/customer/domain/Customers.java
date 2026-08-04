package com.example.samples.s27.customer.domain;

import java.util.Optional;

/**
 * The customer aggregate's port.
 *
 * <p>{@link #find} answers empty for a suppressed row, and the domain cannot tell that from "no such
 * customer" — which is what the infrastructure switch means and the reason the port has no way to ask.
 * {@link #suppress} and {@link #restore} are the switch itself, and they are on the port rather than on the
 * aggregate because they are not operations on a customer; they are operations on a <em>row</em>.
 *
 * <p>That split is the whole modelling answer to the catalogue's first question, expressed as an interface:
 * anything the domain can explain is a method on {@link Customer}, anything only the persistence layer knows
 * is a method here.
 */
public interface Customers {

  /** The customer, unless there is no such row or the row is suppressed. */
  Optional<Customer> find(CustomerId id);

  void save(Customer customer);

  /**
   * Hide the row. Not a domain operation: nothing is recorded, nothing is announced, and no rule will ever
   * read the result.
   *
   * @return true if a row was hidden
   */
  boolean suppress(CustomerId id);

  /**
   * Un-hide it. Present because it is trivially possible for an infrastructure switch — which is worth
   * noticing, since it is the one thing a switch does better than domain state, and the reason people reach
   * for it: an operator can undo a mistake without the model having a concept for it.
   *
   * @return true if a row was restored
   */
  boolean restore(CustomerId id);
}

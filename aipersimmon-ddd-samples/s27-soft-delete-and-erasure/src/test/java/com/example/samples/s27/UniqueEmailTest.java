package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s27.customer.application.SuppressCustomer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A logical delete and a unique index, which do not get on.
 *
 * <p>The naive index cannot tell a live row from a hidden one, so suppressing a customer takes their address out
 * of circulation permanently and the person cannot come back. It is the second-most-common consequence of
 * choosing a logical delete and it is never mentioned when the choice is made.
 */
class UniqueEmailTest extends CustomerTestBase {

  @AfterEach
  void putTheIndexBack() {
    jdbc.execute("DROP INDEX IF EXISTS uq_s27_customer_email");
    jdbc.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_s27_customer_email_live"
            + " ON s27_customer (email) WHERE deleted = FALSE");
  }

  /** Two live customers still cannot share an address. The partial index constrains what it should. */
  @Test
  void twoliveCustomersCannotShareAnAddress() {
    registerAlice();

    assertThatThrownBy(() -> register("cust-bob", ALICE_EMAIL))
        .hasMessageContaining("already taken");
  }

  /**
   * With the partial index (V2), a suppressed customer stops holding their address.
   *
   * <p>Which is what "as far as the application is concerned this row does not exist" has to mean if it means
   * anything. Note that the old row keeps its email in the table — nothing was rewritten — it simply stops
   * constraining anybody.
   */
  @Test
  void asuppressedCustomerReleasesTheirAddress() {
    registerAlice();
    commandBus.send(new SuppressCustomer(ALICE));

    register("cust-alice-again", ALICE_EMAIL);

    assertThat(customers.find(id("cust-alice-again"))).isPresent();
    // Both rows are there, with the same address, and only one is visible.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM s27_customer WHERE email = ?", Long.class, ALICE_EMAIL))
        .isEqualTo(2);
  }

  /**
   * The control: with the naive index of V1, the same sequence is refused.
   *
   * <p>Measured by putting the old index back, because a claim about what a plain {@code UNIQUE} does to a
   * logically-deleted row is exactly the kind of thing that is obvious and wrong half the time. The refusal is
   * also indistinguishable, from the caller's side, from "somebody else has that address" — the person is told
   * their own old address is taken, and nobody without SQL access can explain why.
   */
  @Test
  void withThenaiveIndexTheAddressStaysTakenForEver() {
    jdbc.execute("DROP INDEX uq_s27_customer_email_live");
    jdbc.execute("CREATE UNIQUE INDEX uq_s27_customer_email ON s27_customer (email)");
    registerAlice();
    commandBus.send(new SuppressCustomer(ALICE));

    assertThatThrownBy(() -> register("cust-alice-again", ALICE_EMAIL))
        .hasMessageContaining("already taken");
  }

  /**
   * And an erased customer's tombstone occupies the index too, which is why it carries the id.
   *
   * <p>An erasure writes {@code erased+<id>@invalid} into a uniquely-indexed column. A constant tombstone would
   * make the <em>second</em> erasure a duplicate-key failure — a compliance operation that works once per
   * database. Measured by trying to write one customer's tombstone onto another row.
   */
  @Test
  void thetombstoneHasToBeUniquePerCustomer() {
    registerAlice();
    register("cust-bob", "bob@example.com");
    drainTheOutbox();
    erase(ALICE);
    erase("cust-bob");

    assertThat(rawRow(ALICE).get("email")).isEqualTo("erased+" + ALICE + "@invalid");
    assertThat(rawRow("cust-bob").get("email")).isEqualTo("erased+cust-bob@invalid");

    // What a constant tombstone would have run into.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE s27_customer SET email = ? WHERE id = ?",
                    "erased+" + ALICE + "@invalid",
                    "cust-bob"))
        .hasMessageContaining("uq_s27_customer_email_live");
  }
}

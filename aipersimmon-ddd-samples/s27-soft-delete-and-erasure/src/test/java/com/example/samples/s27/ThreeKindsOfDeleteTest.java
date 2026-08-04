package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s27.customer.application.RestoreCustomer;
import com.example.samples.s27.customer.application.SuppressCustomer;
import com.example.samples.s27.customer.domain.CustomerStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three deletions side by side, distinguished by what each one leaves behind.
 *
 * <p>This is the table an argument about "should we soft delete" should start from. Every row of it is measured.
 */
class ThreeKindsOfDeleteTest extends CustomerTestBase {

  /**
   * (1) Domain state: the customer is still there, still readable, and can say why.
   *
   * <p>Which is the test for whether a soft delete belongs in the model: somebody will ask why, somebody will
   * ask for it back, and somebody will ask for a list of them. All three are ordinary queries against a status
   * column and none of them are possible against a hidden row.
   */
  @Test
  void closingLeavesAReadableCustomerThatKnowsWhy() {
    registerAlice();

    close(ALICE, "moved to a competitor");

    assertThat(customers.find(id(ALICE)))
        .isPresent()
        .get()
        .satisfies(
            customer -> {
              assertThat(customer.status()).isEqualTo(CustomerStatus.CLOSED);
              assertThat(customer.closedReason()).contains("moved to a competitor");
              // The personal data is untouched: a closure is not a privacy operation.
              assertThat(customer.email().value()).isEqualTo(ALICE_EMAIL);
            });
  }

  /** And it goes back, because domain state is symmetric. */
  @Test
  void closingIsReversible() {
    registerAlice();
    close(ALICE, "changed their mind later");

    commandBus.send(new com.example.samples.s27.customer.application.ReopenCustomer(ALICE));

    assertThat(customers.find(id(ALICE)).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
    assertThat(customers.find(id(ALICE)).orElseThrow().closedReason()).isEmpty();
  }

  /**
   * (2) The infrastructure switch: the row is still in the table and the application cannot see it.
   *
   * <p>Both halves matter. The row is there — so nothing was destroyed, and an operator with SQL can find it —
   * and every statement the mapper builds excludes it, so no amount of application code can. That gap is the
   * mechanism, and it is also the reason a suppressed row is indistinguishable from a nonexistent one to
   * everything above the repository.
   */
  @Test
  void suppressingHidesTheRowWithoutRemovingIt() {
    registerAlice();

    assertThat(commandBus.send(new SuppressCustomer(ALICE))).isTrue();

    assertThat(customers.find(id(ALICE))).isEmpty();
    assertThat(rawRowCount(ALICE)).isEqualTo(1);
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(true);
    // Untouched: the switch says nothing about the customer, so nothing about the customer changed.
    assertThat(rawRow(ALICE).get("email")).isEqualTo(ALICE_EMAIL);
    assertThat(rawRow(ALICE).get("status")).isEqualTo("ACTIVE");
  }

  /** Reversible too — but only through SQL nobody generated for us. */
  @Test
  void restoringNeedsAHandWrittenStatement() {
    registerAlice();
    commandBus.send(new SuppressCustomer(ALICE));

    assertThat(commandBus.send(new RestoreCustomer(ALICE))).isTrue();

    assertThat(customers.find(id(ALICE))).isPresent();
    assertThat(rawRow(ALICE).get("deleted")).isEqualTo(false);
  }

  /** Suppressing what is already hidden changes nothing, and says so. */
  @Test
  void suppressingTwiceIsANoOp() {
    registerAlice();
    commandBus.send(new SuppressCustomer(ALICE));

    assertThat(commandBus.send(new SuppressCustomer(ALICE))).isFalse();
  }

  /**
   * (3) The erasure: the row stays, the person does not.
   *
   * <p>Not a delete in any sense — the row is visible, the id is intact, the version moved — and that is the
   * point. What is gone is the personal data, replaced by values the model still accepts, plus a date recording
   * that it happened.
   */
  @Test
  void erasingOverwritesThePersonAndKeepsTheRecord() {
    registerAlice();
    drainTheOutbox();

    erase(ALICE);

    Map<String, Object> row = rawRow(ALICE);
    assertThat(row.get("email")).isEqualTo("erased+" + ALICE + "@invalid");
    assertThat(row.get("display_name")).isEqualTo("(erased)");
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("status")).isEqualTo("CLOSED");
    assertThat(row.get("closed_reason")).isEqualTo("erased");
    assertThat(row.get("erased_at")).isNotNull();
    // Still visible, and still the same row.
    assertThat(row.get("deleted")).isEqualTo(false);
    assertThat(customers.find(id(ALICE))).isPresent();
  }

  /**
   * The three are orthogonal, which is why one boolean could not have carried them.
   *
   * <p>A closed, suppressed, erased customer is a coherent state: the business ended the relationship, an
   * operator hid the row, and a regulator required the data gone. Anyone who modelled all three as one
   * {@code deleted} flag would have had to decide which of the three it meant, and the other two would have
   * been recorded nowhere.
   */
  @Test
  void allThreeCanBeTrueAtOnceAndMeanDifferentThings() {
    registerAlice();
    close(ALICE, "moved to a competitor");
    drainTheOutbox();
    erase(ALICE);
    commandBus.send(new SuppressCustomer(ALICE));

    Map<String, Object> row = rawRow(ALICE);
    assertThat(row.get("status")).isEqualTo("CLOSED");
    assertThat(row.get("erased_at")).isNotNull();
    assertThat(row.get("deleted")).isEqualTo(true);
    assertThat(customers.find(id(ALICE))).isEmpty();
  }
}

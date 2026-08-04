package com.example.samples.s01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.operationlog.cqrs.template.RestrictedTemplate;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.example.samples.s01.ordering.application.PlaceOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the annotation's template language will and will not let into an audit row.
 *
 * <p>No Spring here: {@code RestrictedTemplate} is public and compiling one is the whole mechanism. These
 * are the assertions behind "the allowlist is the redaction policy" — the component's {@code Redactor}
 * strips control characters and enforces size budgets, and does <em>not</em> decide what is sensitive. What
 * reaches a row is what a template names.
 */
class RestrictedTemplateTest {

  private static final Set<String> ROOTS = Set.of("input");

  private static final PlaceOrder INPUT =
      new PlaceOrder("customer-1", List.of(new PlaceOrder.Line("SKU-1", 2)));

  @Test
  void apropertyPathIsInterpolated() {
    RestrictedTemplate template = RestrictedTemplate.compile("for ${input.customerId}", ROOTS);

    assertThat(template.render(Map.of("input", INPUT))).isEqualTo("for customer-1");
  }

  /**
   * {@code mask} is the library's answer to "record that it changed without recording what it is" — and it
   * is masking, not removal.
   *
   * <p>First character, three stars, last character. Enough to confirm to a support agent that the value on
   * file matches what the caller is reading out, and not enough to be the value. Worth being precise about
   * what it still discloses: the first and last character, and that the value was longer than two — so it is
   * a reasonable choice for a phone number or an account reference and a poor one for anything whose first
   * and last characters are most of the secret.
   */
  @Test
  void maskKeepsTheFirstAndLastCharacterAndNothingElse() {
    RestrictedTemplate template = RestrictedTemplate.compile("${mask(input.customerId)}", ROOTS);

    assertThat(template.render(Map.of("input", INPUT))).isEqualTo("c***1");
  }

  /** Two characters or fewer become {@code **}: there is no first-and-last to keep without keeping all of it. */
  @Test
  void ashortValueIsMaskedEntirely() {
    RestrictedTemplate template = RestrictedTemplate.compile("${mask(input.customerId)}", ROOTS);
    PlaceOrder shortId = new PlaceOrder("ab", List.of(new PlaceOrder.Line("SKU-1", 1)));

    assertThat(template.render(Map.of("input", shortId))).isEqualTo("**");
  }

  /**
   * An unknown root fails at compile time, which for an annotation means at startup.
   *
   * <p>This is why the templates are a string and still safe to refactor around: renaming a command's field
   * turns the audit row into a failed boot rather than into an empty summary discovered months later by
   * whoever needed it.
   */
  @Test
  void anunknownRootIsRefused() {
    assertThatThrownBy(() -> RestrictedTemplate.compile("${entity.name}", ROOTS))
        .isInstanceOf(OperationLogException.class);
  }

  @Test
  void anunknownFunctionIsRefused() {
    assertThatThrownBy(() -> RestrictedTemplate.compile("${encrypt(input.customerId)}", ROOTS))
        .isInstanceOf(OperationLogException.class);
  }

  @Test
  void amalformedPlaceholderIsRefused() {
    assertThatThrownBy(() -> RestrictedTemplate.compile("${input.customerId", ROOTS))
        .isInstanceOf(OperationLogException.class);
  }

  /**
   * A path that does not resolve renders empty rather than throwing.
   *
   * <p>The one place the language is lenient, and the trade is deliberate: an audit row with a gap in its
   * summary is better than a command that fails because its log line could not be rendered. It does mean a
   * wrong-but-valid path is silent — the compile-time root check is what keeps that to a typo inside a
   * known object.
   */
  @Test
  void anullValueRendersAsNothing() {
    RestrictedTemplate template = RestrictedTemplate.compile("[${input.customerId}]", ROOTS);
    PlaceOrder noCustomer = new PlaceOrder(null, List.of());

    assertThat(template.render(Map.of("input", noCustomer))).isEqualTo("[]");
  }
}

package com.aipersimmon.ddd.operationlog.cqrs.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestrictedTemplateTest {

  record Sample(String name, String note, String secret, String reason) {}

  private static final Set<String> ROOTS = Set.of("input");

  private String render(String template, Sample input) {
    return RestrictedTemplate.compile(template, ROOTS).render(Map.of("input", input));
  }

  @Test
  void renders_literal_and_path() {
    assertEquals("Hi Bob!", render("Hi ${input.name}!", new Sample("Bob", null, null, null)));
  }

  @Test
  void applies_truncate_mask_and_default_value() {
    Sample s = new Sample("Bob", "abcdefg", "abcdef", null);
    assertEquals("abc", render("${truncate(input.note, 3)}", s));
    assertEquals("a***f", render("${mask(input.secret)}", s));
    assertEquals("n/a", render("${defaultValue(input.reason, 'n/a')}", s));
  }

  @Test
  void missing_property_is_null_safe_empty() {
    assertEquals("x=", render("x=${input.name}", new Sample(null, null, null, null)));
  }

  @Test
  void rejects_unknown_root_at_compile() {
    assertThrows(OperationLogException.class, () -> RestrictedTemplate.compile("${bad.x}", ROOTS));
  }

  @Test
  void rejects_unknown_function_at_compile() {
    assertThrows(
        OperationLogException.class, () -> RestrictedTemplate.compile("${danger(input.x)}", ROOTS));
  }

  @Test
  void rejects_wrong_arity_at_compile() {
    assertThrows(
        OperationLogException.class,
        () -> RestrictedTemplate.compile("${truncate(input.x)}", ROOTS));
  }

  @Test
  void rejects_unterminated_placeholder() {
    assertThrows(OperationLogException.class, () -> RestrictedTemplate.compile("${input.x", ROOTS));
  }
}

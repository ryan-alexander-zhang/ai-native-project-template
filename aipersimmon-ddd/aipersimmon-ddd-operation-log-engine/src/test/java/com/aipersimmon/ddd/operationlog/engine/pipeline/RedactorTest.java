package com.aipersimmon.ddd.operationlog.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.operationlog.model.OperationChange;
import com.aipersimmon.ddd.operationlog.model.OperationDetail;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Directly exercises the {@link Redactor} strip/truncate/cap branches on every text-bearing part.
 */
class RedactorTest {

  // summaryMaxChars=5, maxChanges=1, maxDetails=1, maxValueChars=4
  private final Redactor redactor = new Redactor(new OperationLogLimits(5, 1, 1, 4));

  @Test
  void summary_is_stripped_of_newlines_then_truncated() {
    assertEquals("ab cd", redactor.summary("ab\ncd\nefgh"));
    assertNull(redactor.summary(null));
    assertEquals("abc", redactor.summary("abc"));
  }

  @Test
  void changes_are_capped_and_their_values_stripped_and_truncated() {
    List<OperationChange> out =
        redactor.changes(
            List.of(
                new OperationChange("f1", "la\nbel", "be\rfore", "afterlong"),
                new OperationChange("f2", "L2", "x", "y")));
    assertEquals(1, out.size(), "capped at maxChanges=1");
    OperationChange first = out.get(0);
    assertEquals("f1", first.field(), "field is not redacted");
    assertEquals("la b", first.label(), "newline stripped then truncated to 4");
    assertEquals("be f", first.before(), "carriage return stripped then truncated to 4");
    assertEquals("afte", first.after(), "truncated to 4");
  }

  @Test
  void details_are_capped_and_values_redacted() {
    List<OperationDetail> out =
        redactor.details(
            List.of(new OperationDetail("n1", "va\nlue123"), new OperationDetail("n2", "v2")));
    assertEquals(1, out.size(), "capped at maxDetails=1");
    assertEquals("n1", out.get(0).name());
    assertEquals("va l", out.get(0).value(), "newline stripped then truncated to 4");
  }
}

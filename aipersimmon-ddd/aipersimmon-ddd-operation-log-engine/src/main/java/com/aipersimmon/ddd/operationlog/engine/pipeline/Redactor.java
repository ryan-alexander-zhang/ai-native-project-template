package com.aipersimmon.ddd.operationlog.engine.pipeline;

import com.aipersimmon.ddd.operationlog.model.OperationChange;
import com.aipersimmon.ddd.operationlog.model.OperationDetail;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies log-injection stripping (CR/LF removal) and size budgets to the free-text and collection
 * parts of a draft. It does not decide what is allowlisted — that is the definition/annotation's
 * job; it only enforces bounds and neutralizes control characters on values already chosen for
 * recording.
 */
final class Redactor {

  private final OperationLogLimits limits;

  Redactor(OperationLogLimits limits) {
    this.limits = limits;
  }

  String summary(String summary) {
    return truncate(strip(summary), limits.summaryMaxChars());
  }

  List<OperationChange> changes(List<OperationChange> changes) {
    List<OperationChange> out = new ArrayList<>();
    for (OperationChange c : changes) {
      if (out.size() >= limits.maxChanges()) {
        break;
      }
      out.add(
          new OperationChange(c.field(), value(c.label()), value(c.before()), value(c.after())));
    }
    return out;
  }

  List<OperationDetail> details(List<OperationDetail> details) {
    List<OperationDetail> out = new ArrayList<>();
    for (OperationDetail d : details) {
      if (out.size() >= limits.maxDetails()) {
        break;
      }
      out.add(new OperationDetail(d.name(), value(d.value())));
    }
    return out;
  }

  private String value(String v) {
    return truncate(strip(v), limits.maxValueChars());
  }

  private static String strip(String s) {
    return s == null ? null : s.replace('\r', ' ').replace('\n', ' ');
  }

  private static String truncate(String s, int max) {
    if (s == null || s.length() <= max) {
      return s;
    }
    return s.substring(0, max);
  }
}

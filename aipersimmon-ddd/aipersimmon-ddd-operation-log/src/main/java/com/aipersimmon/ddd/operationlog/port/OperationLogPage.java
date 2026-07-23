package com.aipersimmon.ddd.operationlog.port;

import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import java.util.List;

/** One page of query results plus the token for the next page ({@code null} when exhausted). */
public record OperationLogPage(List<OperationLogEntry> items, String nextCursor) {

  /** Freezes the items list. */
  public OperationLogPage {
    items = items == null ? List.of() : List.copyOf(items);
  }
}

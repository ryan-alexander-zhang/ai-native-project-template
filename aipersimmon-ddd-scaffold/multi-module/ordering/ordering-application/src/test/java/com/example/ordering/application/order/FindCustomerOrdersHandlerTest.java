package com.example.ordering.application.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The page size a caller asks for is a request, not an instruction. A list endpoint that honours
 * any number is a way to ask the database for a million rows in one statement, so the handler
 * clamps — and clamping is asserted here rather than left to the SQL, where nothing would show it
 * happening.
 */
class FindCustomerOrdersHandlerTest {

  private final RecordingQueries queries = new RecordingQueries();
  private final FindCustomerOrdersHandler handler = new FindCustomerOrdersHandler(queries);

  @Test
  void anUnreasonableSizeIsCappedRatherThanRefused() {
    handler.handle(new FindCustomerOrders("CUST-1", null, 10_000));

    assertEquals(FindCustomerOrdersHandler.MAX_SIZE, queries.size);
  }

  @Test
  void anAbsentSizeGetsTheDefault() {
    handler.handle(new FindCustomerOrders("CUST-1", null, 0));

    assertEquals(FindCustomerOrdersHandler.DEFAULT_SIZE, queries.size);
  }

  @Test
  void aReasonableSizeIsPassedThroughWithTheCursor() {
    Cursor cursor = Cursor.of("0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f");

    handler.handle(new FindCustomerOrders("CUST-1", cursor, 5));

    assertEquals(5, queries.size);
    assertEquals(cursor, queries.after);
    assertEquals("CUST-1", queries.customerId);
  }

  /** Captures what the handler asked the read side for. */
  private static final class RecordingQueries implements OrderQueries {

    private String customerId;
    private Cursor after;
    private int size;

    @Override
    public Slice<OrderListItem> byCustomer(String customerId, Cursor after, int size) {
      this.customerId = customerId;
      this.after = after;
      this.size = size;
      return new Slice<>(List.of(), null);
    }
  }
}

package com.example.ordering.infrastructure.persistence.order;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.example.ordering.application.order.OrderListItem;
import com.example.ordering.application.order.OrderQueries;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Answers the order list from {@link OrderListMapper}.
 *
 * <p>The one piece of logic here is how "is there a next page" is decided: fetch {@code size + 1}
 * rows and, if the extra one came back, drop it and hand out a cursor. The alternative — a {@code
 * COUNT(*)} to compare against — is a second scan of the same rows for information the query
 * already had.
 *
 * <p>A {@code @Component}, not a {@code @Repository}: the stereotype marks an implementation of a
 * domain repository port, and this implements neither a domain port nor a repository. It is the
 * read side, which the aggregate boundary does not govern.
 */
@Component
public class MyBatisOrderQueries implements OrderQueries {

  private final OrderListMapper mapper;

  public MyBatisOrderQueries(OrderListMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Slice<OrderListItem> byCustomer(String customerId, Cursor after, int size) {
    List<OrderListItem> rows =
        mapper.byCustomer(customerId, after == null ? null : after.value(), size + 1);
    if (rows.size() <= size) {
      return new Slice<>(rows, null);
    }
    List<OrderListItem> page = rows.subList(0, size);
    // The cursor is the last id on this page, which is also the position to resume from — see the
    // note on OrderListMapper about why a UUIDv7 id can be the cursor.
    return new Slice<>(page, Cursor.of(page.get(page.size() - 1).id()));
  }
}

package com.example.samples.s20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.page.Page;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s20.ordering.application.BrowseOrders;
import com.example.samples.s20.ordering.application.BrowseOrdersWithTotals;
import com.example.samples.s20.ordering.application.ConfirmOrder;
import com.example.samples.s20.ordering.application.OrderFilter;
import com.example.samples.s20.ordering.application.OrderSort;
import com.example.samples.s20.ordering.application.OrderSummary;
import com.example.samples.s20.ordering.application.PageRequest;
import com.example.samples.s20.ordering.application.PlaceOrder;
import com.example.samples.s20.ordering.domain.OrderStatus;
import com.example.samples.s20.ordering.infrastructure.OffsetPager;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The read side's contract: the two shapes, the cursor, the ordering, and what offset loses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresServiceConnection.class, RecordingStatements.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class QueryContractTest {

  private static final OrderFilter OPEN_ORDERS = new OrderFilter(null, OrderStatus.PLACED);

  /** More pages than any test here has rows for; reaching it means the walk is not progressing. */
  private static final int RUNAWAY_GUARD = 20;

  @Autowired private CommandBus commandBus;
  @Autowired private QueryBus queryBus;
  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OffsetPager offsetPager;
  @Autowired private RecordingStatements.Log statements;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s20_order");
    statements.reset();
  }

  @Test
  void theDefaultShapeIsASliceAndTheCursorTravelsAsAPlainString() {
    place(5, "alice");

    ResponseEntity<String> response = http.getForEntity("/orders?size=2", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<List<?>>read(response.getBody(), "$.items")).hasSize(2);
    // CursorJacksonModule renders the Cursor as its token, not as {"value": "..."} — the wire shape
    // is {"items": [...], "nextCursor": "..."} and nothing else. No success envelope wraps it.
    assertThat(JsonPath.<String>read(response.getBody(), "$.nextCursor")).isNotBlank();
    assertThat(response.getBody()).doesNotContain("totalElements");
  }

  @Test
  void walkingEveryPageVisitsEveryRowExactlyOnce() {
    place(7, "alice");
    List<String> expected = openOrderIdsNewestFirst();

    Walk walk = walk(OrderSort.NEWEST_FIRST, 3);

    assertThat(walk.ids()).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(walk.pages()).isEqualTo(3);
  }

  @Test
  void theLastPageIsExactlyFullAndStillSaysThereIsNoNextPage() {
    place(6, "alice");

    Slice<OrderSummary> first =
        queryBus.ask(new BrowseOrders(new PageRequest(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, null)));
    Slice<OrderSummary> second =
        queryBus.ask(
            new BrowseOrders(
                new PageRequest(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, first.nextCursor())));

    // Six rows, three at a time: the second page is full to the brim and is still the last one.
    // Handing out a cursor because a page came back full is the classic bug — the client follows it
    // and renders an empty page with a "next" button of its own.
    assertThat(second.items()).hasSize(3);
    assertThat(second.hasNext()).isFalse();
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  void aSliceIsOneStatementAndTotalsCostASecond() {
    place(5, "alice");

    statements.reset();
    queryBus.ask(new BrowseOrders(PageRequest.firstPage(2)));
    List<String> forTheSlice = statements.touching("s20_order");

    statements.reset();
    queryBus.ask(new BrowseOrdersWithTotals(PageRequest.firstPage(2)));
    List<String> forThePage = statements.touching("s20_order");

    // One statement, with a LIMIT and no OFFSET: the seek predicate does the positioning, so page
    // 10,000 reads as few rows as page 1.
    assertThat(forTheSlice).hasSize(1);
    assertThat(forTheSlice.get(0)).contains("limit").doesNotContain("offset");
    // Totals are a second statement, and it is the one that scans: the slice reads at most size + 1
    // rows, the count reads every matching row. That is the price of a number on a screen.
    assertThat(forThePage).hasSize(2);
    assertThat(forThePage).anyMatch(sql -> sql.contains("count("));
  }

  @Test
  void aRowLeavingTheSetMakesTheOffsetPagerSkipAnother() {
    place(6, "alice");
    List<String> before = openOrderIdsNewestFirst();

    List<String> firstPage =
        offsetPager.fetchPage(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, 1).stream()
            .map(OrderSummary::id)
            .toList();
    // While the client reads page 1, one of the orders it just saw is confirmed and leaves the set.
    commandBus.send(new ConfirmOrder(before.get(0)));
    List<String> secondPage =
        offsetPager.fetchPage(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, 2).stream()
            .map(OrderSummary::id)
            .toList();

    // Every later row moved up one, so OFFSET 3 now points past the row that took position 3. It is
    // still open, still matches the filter, and this client will never be shown it. Nothing failed.
    String skipped = before.get(3);
    assertThat(firstPage).containsExactly(before.get(0), before.get(1), before.get(2));
    assertThat(firstPage).doesNotContain(skipped);
    assertThat(secondPage).doesNotContain(skipped);
    assertThat(openOrderIdsNewestFirst()).contains(skipped);
  }

  @Test
  void theKeysetPagerSkipsNothingWhenARowLeavesTheSet() {
    place(6, "alice");
    List<String> before = openOrderIdsNewestFirst();

    Slice<OrderSummary> firstPage =
        queryBus.ask(new BrowseOrders(new PageRequest(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, null)));
    commandBus.send(new ConfirmOrder(before.get(0)));
    Slice<OrderSummary> secondPage =
        queryBus.ask(
            new BrowseOrders(
                new PageRequest(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 3, firstPage.nextCursor())));

    // The cursor names a row, not a count, so a row leaving the set above it changes nothing about
    // where "after this one" is. Every row still in the set appears exactly once across the pages.
    List<String> walked = new ArrayList<>();
    firstPage.items().forEach(item -> walked.add(item.id()));
    secondPage.items().forEach(item -> walked.add(item.id()));
    assertThat(walked).containsExactlyElementsOf(before).doesNotHaveDuplicates();
  }

  @Test
  void afabricatedCursorIsRefusedAsAProblemNotAsAFault() {
    place(3, "alice");

    ResponseEntity<String> response = http.getForEntity("/orders?cursor=page-2", String.class);

    // A read contract has failure modes, and a client that mangles a token deserves to be told so.
    // Left to a NumberFormatException this would be a 500 and a stack trace in the log.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(response.getBody(), "$.code"))
        .isEqualTo("ordering.malformed-cursor");
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/validation-failed");
  }

  @Test
  void acursorIsRefusedWhenTheQuestionChangesUnderIt() {
    place(4, "alice");
    place(4, "bob");

    String cursor =
        JsonPath.read(
            http.getForEntity("/orders?status=PLACED&size=2", String.class).getBody(),
            "$.nextCursor");
    ResponseEntity<String> reused =
        http.getForEntity(
            "/orders?status=PLACED&customerId=alice&cursor=" + cursor, String.class);

    // The token describes a position in a result set that the new filter does not have. Honouring it
    // would return a page that is neither the first nor the next; refusing is the only right answer.
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(reused.getBody(), "$.code"))
        .isEqualTo("ordering.cursor-does-not-match-query");
  }

  @Test
  void anUnknownOrderingNeverReachesAStatement() {
    place(3, "alice");
    statements.reset();

    ResponseEntity<String> response =
        http.getForEntity("/orders?sort=placed_at%20desc%3B%20drop%20table", String.class);

    // Binding to an enum is the whole whitelist: the value is refused during conversion, before a
    // query object exists, so nothing a client chose ever reaches SQL.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(statements.touching("s20_order")).isEmpty();
  }

  @Test
  void apageSizeBeyondTheCeilingIsRefusedAtEveryEntry() {
    ResponseEntity<String> overHttp = http.getForEntity("/orders?size=5000", String.class);

    assertThat(overHttp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(overHttp.getBody(), "$.code"))
        .isEqualTo("ordering.page-size-out-of-range");

    // And on the bus, because the query side has no validation interceptor to lean on: a ceiling
    // enforced only at the web edge is a ceiling the export job does not have.
    assertThatThrownBy(() -> new PageRequest(OPEN_ORDERS, OrderSort.NEWEST_FIRST, 5000, null))
        .isInstanceOf(ApplicationException.class)
        .extracting(thrown -> ((ApplicationException) thrown).errorCode().map(code -> code.code()))
        .isEqualTo(Optional.of("ordering.page-size-out-of-range"));
  }

  @Test
  void filtersComposeWithoutAnySqlBeingBuiltByHand() {
    place(3, "alice");
    List<String> bobs = place(2, "bob");
    commandBus.send(new ConfirmOrder(bobs.get(0)));

    Slice<OrderSummary> alicesOpenOrders =
        queryBus.ask(
            new BrowseOrders(
                new PageRequest(
                    new OrderFilter("alice", OrderStatus.PLACED),
                    OrderSort.NEWEST_FIRST,
                    10,
                    null)));

    assertThat(alicesOpenOrders.items())
        .allSatisfy(
            item -> {
              assertThat(item.customerId()).isEqualTo("alice");
              assertThat(item.status()).isEqualTo("PLACED");
            });
    assertThat(alicesOpenOrders.items()).hasSize(3);
    assertThat(alicesOpenOrders.hasNext()).isFalse();
  }

  @Test
  void theOldestFirstOrderingWalksTheSameRowsInReverse() {
    place(5, "alice");

    List<String> newestFirst = walk(OrderSort.NEWEST_FIRST, 2).ids();
    List<String> oldestFirst = walk(OrderSort.OLDEST_FIRST, 2).ids();

    // Same total ordering read from the other end. The seek predicate flips with the sort — and a
    // cursor issued under one of them is refused under the other.
    assertThat(oldestFirst).containsExactlyElementsOf(newestFirst.reversed());
  }

  @Test
  void totalsCountEveryMatchingRowAndNotJustThePage() {
    place(5, "alice");
    place(2, "bob");

    Page<OrderSummary> page =
        queryBus.ask(
            new BrowseOrdersWithTotals(
                new PageRequest(
                    new OrderFilter("alice", null), OrderSort.NEWEST_FIRST, 2, null)));

    assertThat(page.items()).hasSize(2);
    assertThat(page.totalElements()).isEqualTo(5);
    assertThat(page.totalPages()).isEqualTo(3);
    // A Page is a Slice that also counts: it still carries a cursor, not a page number, so the way
    // to read the next page does not change with the shape.
    assertThat(page.nextCursor()).isNotNull();
  }

  @Test
  void thePageShapeOnTheWireIsTheSliceShapePlusTotals() {
    place(3, "alice");

    ResponseEntity<String> response = http.getForEntity("/admin/orders?size=2", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<List<?>>read(response.getBody(), "$.items")).hasSize(2);
    assertThat(JsonPath.<String>read(response.getBody(), "$.nextCursor")).isNotBlank();
    assertThat(JsonPath.<Integer>read(response.getBody(), "$.totalElements")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(response.getBody(), "$.totalPages")).isEqualTo(2);
  }

  /** Every page of one ordering, from the first cursor-less request to the one with no next. */
  private Walk walk(OrderSort sort, int size) {
    List<String> ids = new ArrayList<>();
    int pages = 0;
    PageRequest request = new PageRequest(OPEN_ORDERS, sort, size, null);
    while (true) {
      Slice<OrderSummary> slice = queryBus.ask(new BrowseOrders(request));
      slice.items().forEach(item -> ids.add(item.id()));
      pages++;
      if (!slice.hasNext()) {
        return new Walk(ids, pages);
      }
      // A cursor that does not advance turns "read the whole list" into a loop with no end. That is
      // the real failure mode of a broken seek predicate: not a wrong page, but an export that runs
      // for ever — and a test that hangs instead of reporting it is no better. Bounded on purpose.
      assertThat(pages)
          .as("pagination did not terminate: the cursor is not advancing")
          .isLessThan(RUNAWAY_GUARD);
      request = request.resumedAt(slice.nextCursor());
    }
  }

  private record Walk(List<String> ids, int pages) {}

  /** @return the ids, in the order they were placed */
  private List<String> place(int count, String customerId) {
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      ResponseEntity<String> created =
          http.postForEntity(
              "/orders", Map.of("customerId", customerId, "quantity", index + 1), String.class);
      ids.add(JsonPath.read(created.getBody(), "$.id"));
    }
    return ids;
  }

  /** The truth the pagers are measured against, straight from the table. */
  private List<String> openOrderIdsNewestFirst() {
    return jdbc.queryForList(
        "SELECT id FROM s20_order WHERE status = 'PLACED' ORDER BY placed_at DESC, id DESC",
        String.class);
  }
}

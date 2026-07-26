package com.aipersimmon.ddd.cqrs.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract-level tests for the read-side pagination value objects. */
class PaginationContractsTest {

  @Test
  void sliceIsCursorFirstAndReportsHasNext() {
    Slice<String> last = new Slice<>(List.of("a", "b"), null);
    assertFalse(last.hasNext());
    assertNull(last.nextCursor());

    Slice<String> more = new Slice<>(List.of("a"), Cursor.of("b3JkXzE="));
    assertTrue(more.hasNext());
    assertEquals("b3JkXzE=", more.nextCursor().value());
  }

  @Test
  void pageCarriesTotalsAndValidatesThem() {
    Page<String> page = new Page<>(List.of("a"), null, 42L, 5);
    assertEquals(42L, page.totalElements());
    assertEquals(5, page.totalPages());
    assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), null, -1L, 0));
  }

  @Test
  void pageRejectsANegativeTotalPages() {
    assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), null, 0L, -1));
  }

  @Test
  void cursorRejectsBlankValue() {
    assertThrows(IllegalArgumentException.class, () -> Cursor.of(" "));
  }

  @Test
  void cursorRejectsANullValue() {
    assertThrows(IllegalArgumentException.class, () -> Cursor.of(null));
  }

  /**
   * Absent items normalize to an empty list rather than propagating null, so a caller can iterate
   * the result of an empty page without a null check.
   */
  @Test
  void absentItemsBecomeAnEmptyList() {
    assertTrue(new Slice<>(null, null).items().isEmpty());
    assertTrue(new Page<>(null, null, 0L, 0).items().isEmpty());
  }

  /** The item list is copied, so a later mutation of the caller's list cannot alter the result. */
  @Test
  void itemsAreCopiedDefensively() {
    List<String> mutable = new ArrayList<>(List.of("a"));
    Slice<String> slice = new Slice<>(mutable, null);
    Page<String> page = new Page<>(mutable, null, 1L, 1);

    mutable.add("b");

    assertEquals(List.of("a"), slice.items());
    assertEquals(List.of("a"), page.items());
  }
}

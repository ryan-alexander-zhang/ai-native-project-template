package com.example.samples.s26.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The projection's four statements. */
@Mapper
interface SalesBoardMapper {

  /**
   * The incremental update, as an upsert.
   *
   * <p>Upsert rather than "select, then insert or update", because two concurrent first sales of the same
   * product would both find no row and both insert. {@code ON CONFLICT DO UPDATE} settles that inside the
   * database, which is the only place it can be settled without a lock the application has to hold.
   */
  @Insert(
      """
      INSERT INTO s26_product_sales (sku, sold_recently, updated_at)
      VALUES (#{sku}, #{quantity}, now())
      ON CONFLICT (sku) DO UPDATE
        SET sold_recently = s26_product_sales.sold_recently + EXCLUDED.sold_recently,
            updated_at = now()
      """)
  int add(@Param("sku") String sku, @Param("quantity") int quantity);

  /** Clear the projection. Half of a rebuild, and never run on its own. */
  @Delete("DELETE FROM s26_product_sales")
  int clear();

  /**
   * Recompute every row from the source.
   *
   * <p>One statement, so the projection is derived rather than accumulated: whatever state it was in before
   * — a missed increment, a double count, a restore from an old backup — this makes it agree with
   * {@code s26_order_line} again. That is the property a cache cannot have, because a cache has no source
   * to be recomputed from; it <em>is</em> the copy.
   */
  @Insert(
      """
      INSERT INTO s26_product_sales (sku, sold_recently, updated_at)
      SELECT sku, SUM(quantity), now()
        FROM s26_order_line
       WHERE placed_at > #{since}
       GROUP BY sku
      """)
  int rebuildSince(@Param("since") Instant since);

  @Select("SELECT sold_recently FROM s26_product_sales WHERE sku = #{sku}")
  Long soldRecently(@Param("sku") String sku);

  /**
   * The best sellers.
   *
   * <p>Ordered by the figure and then by sku. The tie-break is not cosmetic: an {@code ORDER BY} with ties
   * has no defined order among them, so two calls can disagree about which of two equal rows comes first,
   * and a paged version of this query would then be able to show the same product twice and never show
   * another. S20 makes the same argument about cursors at more length.
   */
  @Select(
      """
      SELECT s.sku, p.name, s.sold_recently
        FROM s26_product_sales s
        JOIN s26_product p ON p.sku = s.sku
       ORDER BY s.sold_recently DESC, s.sku ASC
       LIMIT #{limit}
      """)
  List<TopSellerRow> top(@Param("limit") int limit);
}

package com.example.samples.s26.catalog.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The expensive read, in one statement.
 *
 * <p>Expensive in the way real read paths are expensive: an aggregate over a table that grows with every
 * sale, for a single product page. The index on {@code (sku, placed_at)} keeps it from being a full scan,
 * which is the point — <strong>a query that is slow because it lacks an index does not need a cache, it
 * needs the index.</strong> Reaching for Redis before checking the plan is how a service ends up with both
 * a cache and a table scan.
 *
 * <p>Hand-written rather than assembled by MyBatis-Plus's wrapper API: a correlated aggregate is not
 * something a lambda wrapper expresses, and a read model's SQL is better read than reconstructed. The
 * write path is where the library's base class earns its place; the read path is plain SQL on purpose.
 */
@Mapper
interface ProductDetailMapper {

  @Select(
      """
      SELECT p.sku,
             p.name,
             p.price_cents,
             COALESCE((SELECT SUM(l.quantity)
                         FROM s26_order_line l
                        WHERE l.sku = p.sku
                          AND l.placed_at > #{since}), 0) AS sold_recently
        FROM s26_product p
       WHERE p.sku = #{sku}
      """)
  ProductDetailRow select(@Param("sku") String sku, @Param("since") Instant since);
}

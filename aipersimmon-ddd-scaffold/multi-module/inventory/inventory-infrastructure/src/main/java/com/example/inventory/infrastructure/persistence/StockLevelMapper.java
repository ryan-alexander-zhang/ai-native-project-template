package com.example.inventory.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The read side's one statement for stock levels: every requested SKU in a single query.
 *
 * <p>No tenant predicate here on purpose. {@code stocks} is in {@code
 * aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables}, so the tenant-line interceptor adds {@code
 * tenant_id = ?} as this statement is prepared; writing one by hand would be a second, silently
 * divergent copy of that rule.
 */
@Mapper
public interface StockLevelMapper {

  /** Rows for the SKUs that exist. Absent SKUs are filled in by the caller, not by SQL. */
  @Select(
      """
      <script>
      SELECT sku, available
        FROM inventory.stocks
       WHERE sku IN
       <foreach item="sku" collection="skus" open="(" separator="," close=")">#{sku}</foreach>
      </script>
      """)
  List<StockLevelRow> levelsOf(@Param("skus") List<String> skus);
}

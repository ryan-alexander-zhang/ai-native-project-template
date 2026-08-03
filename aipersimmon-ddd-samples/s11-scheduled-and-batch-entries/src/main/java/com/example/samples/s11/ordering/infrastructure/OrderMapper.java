package com.example.samples.s11.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * The mapper. Two statements are written out rather than built with a wrapper, because in both cases
 * the SQL <em>is</em> the point and a reader should not have to reconstruct it from method calls.
 */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {

  /**
   * The candidate scan: open orders past their deadline, oldest first, bounded.
   *
   * <p>The deadline arrives as an ISO-8601 string with an explicit cast, so the comparison happens in
   * a type the statement names rather than one a driver and a session time zone negotiated.
   */
  @Select(
      """
      SELECT id FROM s11_order
      WHERE status = 'PLACED' AND payment_due_at < CAST(#{asOf} AS timestamptz)
      ORDER BY payment_due_at ASC
      LIMIT #{limit}
      """)
  List<String> selectExpiredIds(@Param("asOf") String asOf, @Param("limit") int limit);

  /**
   * The shortcut this sample exists to argue against. Kept here, not hidden in a test, because the
   * point is that it is the obvious thing to write.
   */
  @Update(
      """
      UPDATE s11_order SET status = 'CLOSED', version = version + 1
      WHERE payment_due_at < CAST(#{asOf} AS timestamptz)
      """)
  int closeEverythingOverdue(@Param("asOf") String asOf);
}

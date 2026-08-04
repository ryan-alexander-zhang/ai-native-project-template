package com.example.samples.s07.payments.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The mapper. One statement is written out, because in that one the SQL is the point. */
@Mapper
interface PaymentMapper extends BaseMapper<PaymentRow> {

  /**
   * The reconciler's candidate scan: unsettled, old enough, not already escalated, oldest first,
   * bounded.
   *
   * <p>The three predicates are the three halves of "stuck" — and the third is the one that is easy to
   * leave out, which turns one alert into an alert every tick forever.
   *
   * <p>The cutoff arrives as an ISO-8601 string with an explicit cast, so the comparison happens in a
   * type the statement names rather than one a driver and a session time zone negotiated between them.
   * There is a partial index over exactly this predicate; see the migration.
   */
  @Select(
      """
      SELECT id FROM s07_payment
      WHERE status IN ('REQUESTED', 'SUBMITTED')
        AND review_reason IS NULL
        AND requested_at < CAST(#{requestedBefore} AS timestamptz)
      ORDER BY requested_at ASC
      LIMIT #{limit}
      """)
  List<String> selectUnsettledIds(
      @Param("requestedBefore") String requestedBefore, @Param("limit") int limit);
}

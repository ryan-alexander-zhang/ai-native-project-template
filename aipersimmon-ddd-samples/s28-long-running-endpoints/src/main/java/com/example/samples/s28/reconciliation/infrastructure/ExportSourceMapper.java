package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ExportRowView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;

/** The source rows, read two ways, and the annotations are the interesting part. */
@Mapper
interface ExportSourceMapper {

  /**
   * Stream a period through a handler.
   *
   * <p>Three things have to line up for this to be a server-side cursor rather than a full read into memory, and
   * <strong>two of them are not in this file</strong>:
   *
   * <ol>
   *   <li>{@code ResultHandler} instead of a {@code List} return — here. Without it MyBatis builds the list
   *       whatever the driver does.
   *   <li>{@code fetchSize} — here, in {@code @Options}. PostgreSQL's driver opens a cursor only when it is told
   *       how much to fetch at a time.
   *   <li>A transaction — <em>at the call site</em>. The same driver ignores {@code fetchSize} entirely when the
   *       connection is in autocommit, and reads every row before returning the first one.
   * </ol>
   *
   * <p>Get any of the three wrong and nothing fails: the query returns all the rows, the code looks like
   * streaming, and the memory is gone. {@code StreamingExportTest} measures the difference the only way that is
   * deterministic — by making the last row of the query fail, and counting how many arrived before it did.
   *
   * <p>{@code FORWARD_ONLY} is stated rather than assumed because a scrollable result set is, by definition, one
   * the driver has to be able to go back through.
   *
   * <p>{@code @ResultType} is required and not decoration: the method returns {@code void}, so there is nothing for
   * MyBatis to infer the row type from, and without it the binding fails at invocation with a message about needing
   * a result map. Worth knowing because the failure arrives when the query runs rather than at startup — which for
   * an export endpoint means the first response has already begun streaming its header.
   */
  @Select(
      "SELECT id, order_ref AS orderRef, amount_cents AS amountCents, note FROM s28_export_row"
          + " WHERE period = #{period} ORDER BY id")
  @Options(fetchSize = 500, resultSetType = ResultSetType.FORWARD_ONLY)
  @ResultType(ExportRowView.class)
  void streamPeriod(
      @Param("period") String period, ResultHandler<ExportRowView> handler);

  /** One keyset page. Ordinary in every way, which is the argument for it. */
  @Select(
      "SELECT id, order_ref AS orderRef, amount_cents AS amountCents, note FROM s28_export_row"
          + " WHERE period = #{period} AND id > #{afterId} ORDER BY id LIMIT #{limit}")
  List<ExportRowView> pageAfter(
      @Param("period") String period, @Param("afterId") long afterId, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM s28_export_row WHERE period = #{period}")
  long countPeriod(@Param("period") String period);
}

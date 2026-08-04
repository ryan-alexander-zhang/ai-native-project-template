package com.example.samples.s28;

import com.example.samples.s28.reconciliation.application.ExportRowView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The export read that returns a list. <strong>Test scope only.</strong>
 *
 * <p>Four lines, no annotations to get wrong, no transaction requirement, and it is what almost everybody writes
 * first — {@code List<Row> findByPeriod(String period)}. It passes every test written against a period with fifty
 * rows in it. On a real month it holds every row of the result in heap at once, and the type signature is the only
 * place that says so.
 *
 * <p>It is here rather than in main code because a sample must not ship the shape it is warning about. What makes it
 * worth shipping at all is that {@code StreamingExportTest} puts the two side by side and counts row objects, so the
 * difference is a measured number rather than an adjective.
 */
@Mapper
public interface BufferedExport {

  @Select(
      "SELECT id, order_ref AS orderRef, amount_cents AS amountCents, note FROM s28_export_row"
          + " WHERE period = #{period} ORDER BY id")
  List<ExportRowView> everything(@Param("period") String period);
}

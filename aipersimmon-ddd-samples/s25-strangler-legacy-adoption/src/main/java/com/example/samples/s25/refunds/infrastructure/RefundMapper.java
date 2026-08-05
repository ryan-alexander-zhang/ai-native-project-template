package com.example.samples.s25.refunds.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The refund table's mapper: generated CRUD, plus the two statements a legacy table forces. */
@Mapper
interface RefundMapper extends BaseMapper<RefundRow> {

  /**
   * The next id from the table's own sequence.
   *
   * <p>{@code pg_get_serial_sequence} rather than a hard-coded {@code legacy_refunds_id_seq}: the sequence's name is a
   * PostgreSQL convention, not a promise, and a table that was once renamed does not have the name you expect. Asking
   * the catalogue costs nothing and cannot be wrong.
   */
  @Select("SELECT nextval(pg_get_serial_sequence('legacy_refunds', 'id'))")
  long nextId();

  /** Whether this order already has an open refund — the rule the monolith never had. */
  @Select(
      "SELECT EXISTS (SELECT 1 FROM legacy_refunds WHERE order_id = #{orderId} AND state = 'OPEN')")
  boolean hasOpenRefund(@Param("orderId") long orderId);
}

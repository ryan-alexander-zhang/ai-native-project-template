package com.example.samples.s24.ordering.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** The order-line table's mapper. */
@Mapper
interface OrderLineMapper {

  @Insert(
      "INSERT INTO s24_ordering_order_line (order_id, line_no, sku, quantity, unit_minor)"
          + " VALUES (#{orderId}, #{lineNo}, #{sku}, #{quantity}, #{unitMinor})")
  void insert(OrderLineRow row);

  @Delete("DELETE FROM s24_ordering_order_line WHERE order_id = #{orderId}")
  void deleteByOrder(@Param("orderId") String orderId);

  @Select(
      "SELECT order_id AS orderId, line_no AS lineNo, sku, quantity, unit_minor AS unitMinor"
          + " FROM s24_ordering_order_line WHERE order_id = #{orderId} ORDER BY line_no")
  List<OrderLineRow> findByOrder(@Param("orderId") String orderId);
}

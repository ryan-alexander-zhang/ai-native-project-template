package com.example.samples.s27.customer.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** The customer table's mapper. */
@Mapper
interface CustomerMapper extends BaseMapper<CustomerRow> {

  /**
   * Un-hide a row, in raw SQL, because MyBatis-Plus cannot express it.
   *
   * <p>Every statement the generated mapper builds carries {@code deleted = false} — that is what the annotation
   * does — so a hidden row is unreachable by {@code selectById}, {@code updateById} and
   * {@code update(entity, wrapper)} alike. There is no un-delete in the API, so restoring one takes a statement
   * written by hand. The sample would rather show that than imply the switch is symmetric.
   *
   * <p>Worth noticing what the asymmetry says: an infrastructure switch is easy to flip and awkward to un-flip,
   * whereas domain state — a {@code status} column the aggregate owns — is equally easy in both directions. It is
   * one more input to the judgement, and it points the opposite way from the usual assumption that a logical
   * delete is the reversible option.
   */
  @Update("UPDATE s27_customer SET deleted = FALSE WHERE id = #{id} AND deleted = TRUE")
  int restoreById(@Param("id") String id);
}

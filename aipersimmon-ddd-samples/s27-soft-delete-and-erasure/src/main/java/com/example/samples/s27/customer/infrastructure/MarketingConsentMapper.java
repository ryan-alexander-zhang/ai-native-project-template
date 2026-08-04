package com.example.samples.s27.customer.infrastructure;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The consent table: append, count, forget.
 *
 * <p>No {@code @TableLogic} here, and no {@code status} either. A consent row is the case where <strong>a real
 * delete is the right answer</strong>: nobody has to be able to prove that a consent once existed, only that it
 * does not now, so there is nothing to keep. Which is the general rule the two tables together make visible —
 * keep the row when its existence is the evidence, delete it when only its contents were ever the point.
 */
@Mapper
interface MarketingConsentMapper {

  @Insert(
      """
      INSERT INTO s27_marketing_consent (customer_id, granted_at, note)
      VALUES (#{customerId}, now(), #{note})
      """)
  int insert(@Param("customerId") String customerId, @Param("note") String note);

  @Select("SELECT COUNT(*) FROM s27_marketing_consent WHERE customer_id = #{customerId}")
  long countFor(@Param("customerId") String customerId);

  @Delete("DELETE FROM s27_marketing_consent WHERE customer_id = #{customerId}")
  int deleteFor(@Param("customerId") String customerId);
}

package com.example.samples.s24.coupons.infrastructure;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Redemption receipts: one conditional insert and one count. */
@Mapper
interface RedemptionMapper {

  /**
   * Claim a receipt, unless one already exists.
   *
   * <p>{@code ON CONFLICT DO NOTHING} rather than select-then-insert, because the two attempts this has to tolerate
   * are exactly the ones that arrive together — an at-least-once delivery overlapping its own retry.
   *
   * @return 1 when this call claimed it, 0 when it was already there
   */
  @Insert(
      "INSERT INTO s24_coupons_redemption (coupon_code, order_id, amount_minor, redeemed_at)"
          + " VALUES (#{code}, #{orderId}, #{amountMinor}, #{at})"
          + " ON CONFLICT (coupon_code, order_id) DO NOTHING")
  int recordIfAbsent(
      @Param("code") String code,
      @Param("orderId") String orderId,
      @Param("amountMinor") long amountMinor,
      @Param("at") Timestamp at);

  @Select("SELECT COUNT(*) FROM s24_coupons_redemption WHERE coupon_code = #{code}")
  int countFor(@Param("code") String code);
}

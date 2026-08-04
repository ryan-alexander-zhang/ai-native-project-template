package com.example.samples.s24.coupons.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * The coupon table's mapper.
 *
 * <p>Every statement it will ever hold names a table starting {@code s24_coupons_}. That is not a convention for its
 * own sake — {@code TableOwnershipTest} reads the SQL out of every mapper in the service and checks it, which makes
 * "has anybody joined across the boundary yet" a question with a mechanical answer instead of a code review.
 */
@Mapper
interface CouponMapper extends BaseMapper<CouponRow> {}

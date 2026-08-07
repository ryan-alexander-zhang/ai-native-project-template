package com.aipersimmon.ddd.archunit.fixture.good.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * A mapper, correctly placed. Matched both ways the rule recognises one: the MyBatis stereotype and
 * the MyBatis-Plus base interface.
 */
@Mapper
public interface GoodOrderRowMapper extends BaseMapper<GoodOrderRow> {}

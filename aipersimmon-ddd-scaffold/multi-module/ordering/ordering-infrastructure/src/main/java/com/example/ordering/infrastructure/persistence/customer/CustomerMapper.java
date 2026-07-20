package com.example.ordering.infrastructure.persistence.customer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for customers (read-only in this app; seeded by Flyway). */
@Mapper
public interface CustomerMapper extends BaseMapper<CustomerDo> {
}

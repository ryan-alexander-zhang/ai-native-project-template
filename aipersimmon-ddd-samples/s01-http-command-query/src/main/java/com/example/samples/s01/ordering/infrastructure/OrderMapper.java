package com.example.samples.s01.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Root-row mapper. {@code @Mapper} is enough: MyBatis's Spring Boot starter registers annotated
 * interfaces without a {@code @MapperScan}. */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {}

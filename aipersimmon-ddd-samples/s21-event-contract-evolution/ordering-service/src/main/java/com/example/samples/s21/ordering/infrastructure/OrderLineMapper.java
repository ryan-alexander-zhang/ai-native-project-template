package com.example.samples.s21.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Child-row mapper. */
@Mapper
interface OrderLineMapper extends BaseMapper<OrderLineRow> {}

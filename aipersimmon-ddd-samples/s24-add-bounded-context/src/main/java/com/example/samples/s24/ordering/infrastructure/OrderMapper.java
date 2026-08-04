package com.example.samples.s24.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** The order table's mapper. Only {@code s24_ordering_} tables, ever. */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {}

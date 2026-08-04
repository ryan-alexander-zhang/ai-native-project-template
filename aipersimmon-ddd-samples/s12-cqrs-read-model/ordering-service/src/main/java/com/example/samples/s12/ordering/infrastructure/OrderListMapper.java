package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Projection mapper. */
@Mapper
interface OrderListMapper extends BaseMapper<OrderListRow> {}

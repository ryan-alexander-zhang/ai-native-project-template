package com.example.samples.s01.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Line-row mapper. */
@Mapper
interface OrderLineMapper extends BaseMapper<OrderLineRow> {}

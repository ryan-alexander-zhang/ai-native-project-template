package com.example.samples.s26.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** The sales-facts table's mapper. */
@Mapper
interface OrderLineMapper extends BaseMapper<OrderLineRow> {}

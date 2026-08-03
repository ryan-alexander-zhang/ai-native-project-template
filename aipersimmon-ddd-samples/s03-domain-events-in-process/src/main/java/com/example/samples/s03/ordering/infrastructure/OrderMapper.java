package com.example.samples.s03.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Order-row mapper. */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {}

package com.example.samples.s22.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Root-row mapper. */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {}

package com.example.samples.s20.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** One mapper, used by both the write path and the read path. */
@Mapper
interface OrderMapper extends BaseMapper<OrderRow> {}

package com.acme.samples.s3.ordering.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderLineMapper extends BaseMapper<OrderLinePo> {}

package com.example.ordering.infrastructure.persistence.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for the order header. */
@Mapper
public interface OrderMapper extends BaseMapper<OrderDo> {}

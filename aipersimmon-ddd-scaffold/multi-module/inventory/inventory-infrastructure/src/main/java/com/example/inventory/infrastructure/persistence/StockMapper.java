package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus mapper for stock levels. */
@Mapper
public interface StockMapper extends BaseMapper<StockDo> {}

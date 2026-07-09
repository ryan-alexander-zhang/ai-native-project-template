package com.acme.samples.s3.inventory.infrastructure.persistence.stock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockMapper extends BaseMapper<StockItemPo> {}

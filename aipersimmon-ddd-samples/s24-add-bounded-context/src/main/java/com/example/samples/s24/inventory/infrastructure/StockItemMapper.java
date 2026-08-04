package com.example.samples.s24.inventory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** The stock table's mapper. Only {@code s24_inventory_} tables. */
@Mapper
interface StockItemMapper extends BaseMapper<StockItemRow> {}

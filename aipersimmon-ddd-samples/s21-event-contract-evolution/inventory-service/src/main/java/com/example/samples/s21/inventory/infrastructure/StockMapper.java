package com.example.samples.s21.inventory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Stock-row mapper. */
@Mapper
interface StockMapper extends BaseMapper<StockRow> {}

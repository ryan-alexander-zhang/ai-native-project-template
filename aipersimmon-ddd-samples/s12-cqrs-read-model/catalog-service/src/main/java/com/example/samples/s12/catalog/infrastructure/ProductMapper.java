package com.example.samples.s12.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Product row mapper. */
@Mapper
interface ProductMapper extends BaseMapper<ProductRow> {}

package com.example.samples.s26.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** The product table's mapper. */
@Mapper
interface ProductMapper extends BaseMapper<ProductRow> {}

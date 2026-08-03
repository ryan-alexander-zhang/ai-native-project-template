package com.example.samples.s08.inventory.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Budget-row mapper. */
@Mapper
interface BudgetMapper extends BaseMapper<BudgetRow> {}

package com.example.samples.s28.reconciliation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** The batch table's mapper. Nothing hand-written: nothing here is a race. */
@Mapper
interface ImportBatchMapper extends BaseMapper<ImportBatchRow> {}

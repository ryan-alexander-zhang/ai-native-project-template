package com.example.samples.s10.points.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Ledger-entry mapper. */
@Mapper
interface PointsEntryMapper extends BaseMapper<PointsEntryRow> {}

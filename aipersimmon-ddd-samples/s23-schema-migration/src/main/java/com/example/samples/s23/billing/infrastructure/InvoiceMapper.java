package com.example.samples.s23.billing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Invoice-row mapper. */
@Mapper
interface InvoiceMapper extends BaseMapper<InvoiceRow> {}

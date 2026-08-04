package com.example.samples.s10.banking.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Account row mapper. */
@Mapper
interface AccountMapper extends BaseMapper<AccountRow> {}

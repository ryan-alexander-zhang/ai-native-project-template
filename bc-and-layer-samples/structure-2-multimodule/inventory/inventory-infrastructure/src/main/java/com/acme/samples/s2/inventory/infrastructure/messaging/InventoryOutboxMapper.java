package com.acme.samples.s2.inventory.infrastructure.messaging;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryOutboxMapper extends BaseMapper<InventoryOutboxPo> {}

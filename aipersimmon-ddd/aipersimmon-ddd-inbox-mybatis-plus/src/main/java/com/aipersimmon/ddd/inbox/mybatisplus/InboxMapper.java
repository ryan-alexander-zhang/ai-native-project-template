package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus mapper for {@link InboxRecord}. Only the inherited
 * {@code insert} is used — recording a handled key. It is registered explicitly by
 * this module's auto-configuration (a {@code MapperFactoryBean}), so the consumer
 * does not need to add it to a {@code @MapperScan}.
 */
public interface InboxMapper extends BaseMapper<InboxRecord> {
}

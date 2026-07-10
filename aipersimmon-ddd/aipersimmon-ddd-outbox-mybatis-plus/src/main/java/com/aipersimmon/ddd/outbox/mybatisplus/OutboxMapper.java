package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus mapper for {@link OutboxRecord}: the inherited {@code insert} (the
 * writer records an event), {@code selectList} (the relay reads unsent rows), and
 * {@code update} (the relay marks a row sent or bumps its attempt count). Registered
 * explicitly by this module's auto-configuration (a {@code MapperFactoryBean}), so
 * the consumer does not need to add it to a {@code @MapperScan}.
 */
public interface OutboxMapper extends BaseMapper<OutboxRecord> {
}

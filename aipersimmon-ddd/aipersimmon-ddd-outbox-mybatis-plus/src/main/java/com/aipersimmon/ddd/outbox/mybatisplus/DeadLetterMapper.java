package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus mapper for {@link DeadLetterRecord}: the inherited {@code insert} (the
 * relay moves a spent message here), {@code selectOne} (replay reads it back), and
 * {@code delete} (replay removes it). Registered explicitly by this module's
 * auto-configuration (a {@code MapperFactoryBean}), like {@link OutboxMapper}, so the
 * consumer does not need to add it to a {@code @MapperScan}.
 */
public interface DeadLetterMapper extends BaseMapper<DeadLetterRecord> {
}

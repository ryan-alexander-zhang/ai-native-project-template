package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * MyBatis-Plus mapper for {@link NonceRecord}: the inherited {@code insert} (first sighting of a
 * nonce, where losing on the primary key <em>is</em> the replay answer) and {@code delete}
 * (dropping an expired entry so the window can roll over). Registered explicitly by this module's
 * auto-configuration (a {@code MapperFactoryBean}), so the consumer does not need to add it to a
 * {@code @MapperScan}.
 */
public interface NonceMapper extends BaseMapper<NonceRecord> {}

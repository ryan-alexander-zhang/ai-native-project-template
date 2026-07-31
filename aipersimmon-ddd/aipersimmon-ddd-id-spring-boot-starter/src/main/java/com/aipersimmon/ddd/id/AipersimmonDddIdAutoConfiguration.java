package com.aipersimmon.ddd.id;

import com.aipersimmon.ddd.core.id.IdGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Binds the default {@link IdGenerator} to the time-ordered {@link Uuidv7IdGenerator} whenever this
 * module is on the classpath, so every framework minting point resolves its id supplier to UUIDv7.
 * {@code @ConditionalOnMissingBean} lets an application supply its own {@link IdGenerator} to
 * override it; a build that omits this module has no {@link IdGenerator} bean, and each consuming
 * auto-configuration falls back to {@code UUID.randomUUID()}.
 */
@AutoConfiguration
public class AipersimmonDddIdAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(IdGenerator.class)
  public IdGenerator idGenerator() {
    return new Uuidv7IdGenerator();
  }
}

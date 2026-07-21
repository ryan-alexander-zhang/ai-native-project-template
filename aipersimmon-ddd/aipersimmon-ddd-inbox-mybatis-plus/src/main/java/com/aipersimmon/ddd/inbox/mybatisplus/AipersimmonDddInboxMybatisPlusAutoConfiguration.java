package com.aipersimmon.ddd.inbox.mybatisplus;

import com.aipersimmon.ddd.application.Inbox;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires a MyBatis-Plus-backed {@link Inbox} once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}. It registers only its own {@link InboxMapper} (a {@code MapperFactoryBean}),
 * so it never triggers or hijacks the consumer's own {@code @MapperScan}. An application can
 * override it by defining its own {@code Inbox} bean. Retention cleanup is opt-in via {@code
 * aipersimmon.ddd.inbox.cleanup.enabled=true}.
 */
@AutoConfiguration(after = MybatisPlusAutoConfiguration.class)
public class AipersimmonDddInboxMybatisPlusAutoConfiguration {

  // Name-scoped so this component always contributes its own named clock and injects it by name,
  // rather than backing off when another component already registered a Clock of the same type. See
  // issue-00026.
  @Bean
  @ConditionalOnMissingBean(name = "inboxClock")
  public Clock inboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<InboxMapper> aipersimmonInboxMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<InboxMapper> factory = new MapperFactoryBean<>(InboxMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean(Inbox.class)
  public Inbox mybatisPlusInbox(
      InboxMapper inboxMapper,
      Clock inboxClock,
      @Value("${aipersimmon.ddd.inbox.consumer:${spring.application.name:aipersimmon}}")
          String consumer) {
    return new MybatisPlusInbox(inboxMapper, inboxClock, consumer);
  }

  /**
   * Enables scheduling and wires the retention cleanup only when opted in, so the common case adds
   * no scheduled beans.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "aipersimmon.ddd.inbox.cleanup.enabled", havingValue = "true")
  @EnableScheduling
  static class InboxCleanupConfiguration {

    @Bean
    @ConditionalOnBean(InboxMapper.class)
    @ConditionalOnMissingBean
    public InboxCleanup inboxCleanup(
        InboxMapper inboxMapper,
        Clock inboxClock,
        @Value("${aipersimmon.ddd.inbox.cleanup.retention-seconds:2592000}")
            long retentionSeconds) {
      return new InboxCleanup(inboxMapper, inboxClock, retentionSeconds);
    }
  }
}

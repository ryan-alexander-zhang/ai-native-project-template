package com.aipersimmon.ddd.inbox.mybatisplus;

import com.aipersimmon.ddd.application.Inbox;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires a MyBatis-Plus-backed {@link Inbox} once MyBatis-Plus has produced a
 * {@code SqlSessionFactory}. It registers only its own {@link InboxMapper} (a
 * {@code MapperFactoryBean}), so it never triggers or hijacks the consumer's own
 * {@code @MapperScan}. An application can override it by defining its own
 * {@code Inbox} bean.
 */
@AutoConfiguration(after = MybatisPlusAutoConfiguration.class)
public class AipersimmonDddInboxMybatisPlusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock inboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean
    public MapperFactoryBean<InboxMapper> aipersimmonInboxMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<InboxMapper> factory = new MapperFactoryBean<>(InboxMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean(Inbox.class)
    public Inbox mybatisPlusInbox(InboxMapper inboxMapper, Clock inboxClock) {
        return new MybatisPlusInbox(inboxMapper, inboxClock);
    }
}

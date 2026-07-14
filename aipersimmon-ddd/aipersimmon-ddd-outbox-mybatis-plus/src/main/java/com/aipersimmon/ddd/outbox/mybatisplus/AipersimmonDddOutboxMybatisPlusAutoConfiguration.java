package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the MyBatis-Plus-backed outbox storage once MyBatis-Plus has produced a
 * {@code SqlSessionFactory}: a writer that implements the integration-event
 * publisher port and a scheduled relay that polls unsent rows and hands them to
 * the {@link OutboxDispatcher} chosen by the storage-agnostic
 * {@link AipersimmonDddOutboxAutoConfiguration} (ordered before this class). It
 * registers only its own {@link OutboxMapper} (a {@code MapperFactoryBean}), so it
 * never triggers or hijacks the consumer's {@code @MapperScan}. Enables scheduling
 * so the relay runs in the background; an application can override any of these
 * beans.
 */
@AutoConfiguration(after = {
        MybatisPlusAutoConfiguration.class,
        AipersimmonDddOutboxAutoConfiguration.class})
@EnableScheduling
public class AipersimmonDddOutboxMybatisPlusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean
    public MapperFactoryBean<OutboxMapper> aipersimmonOutboxMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<OutboxMapper> factory = new MapperFactoryBean<>(OutboxMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean(IntegrationEvents.class)
    public IntegrationEvents outboxWriter(OutboxMapper outboxMapper, Clock outboxClock,
            @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}") String source) {
        return new OutboxWriter(outboxMapper, new ObjectMapper(), outboxClock, source);
    }

    @Bean
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxMapper outboxMapper, OutboxDispatcher outboxDispatcher,
                                   Clock outboxClock,
                                   @Value("${aipersimmon.ddd.outbox.batch-size:100}") int batchSize) {
        return new OutboxRelay(outboxMapper, outboxDispatcher, outboxClock, batchSize);
    }
}

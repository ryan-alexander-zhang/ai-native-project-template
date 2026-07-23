package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.aipersimmon.ddd.operationlog.engine.autoconfigure.AipersimmonDddOperationLogAutoConfiguration;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Wires the MyBatis-Plus {@link OperationLogSink} once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}. Registers only its own {@link OperationLogMapper} (a {@code
 * MapperFactoryBean}), so it never triggers or hijacks the consumer's {@code @MapperScan}. Ordered
 * before the engine so the sink exists when the engine's pipeline (conditional on a sink) is
 * evaluated. Every bean is conditional so an application can override it. Include exactly one
 * storage backend.
 */
@AutoConfiguration(
    after = {
      MybatisPlusAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    },
    before = AipersimmonDddOperationLogAutoConfiguration.class)
public class AipersimmonDddOperationLogMybatisPlusAutoConfiguration {

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<OperationLogMapper> operationLogMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<OperationLogMapper> factory =
        new MapperFactoryBean<>(OperationLogMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(OperationLogMapper.class)
  @ConditionalOnMissingBean(OperationLogSink.class)
  public OperationLogSink operationLogSink(
      OperationLogMapper mapper, DataSource dataSource, ObjectProvider<ObjectMapper> objectMapper) {
    ObjectMapper mapperJson = objectMapper.getIfAvailable(ObjectMapper::new);
    return new MybatisPlusOperationLogSink(mapper, isPostgres(dataSource), mapperJson);
  }

  private static boolean isPostgres(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return connection
          .getMetaData()
          .getDatabaseProductName()
          .toLowerCase(Locale.ROOT)
          .contains("postgresql");
    } catch (SQLException e) {
      throw new OperationLogException("cannot probe the database product for dialect selection", e);
    }
  }
}

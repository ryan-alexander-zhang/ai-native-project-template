package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

/**
 * Hand-wires this module's mappers over a plain {@link DataSource}, for the tests that construct a
 * store directly instead of taking the auto-configured bean.
 *
 * <p>A {@code SpringManagedTransactionFactory}, so a mapper call enlists in an ambient Spring
 * transaction exactly as the auto-configured mappers do — the claim tests depend on it: a session
 * committing per statement would hide the CAS race they exist to pin.
 */
final class OutboxMappers {

  private OutboxMappers() {}

  static SqlSessionTemplate session(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(OutboxMapper.class);
    configuration.addMapper(DeadLetterMapper.class);
    configuration.addMapper(OutboxSchemaMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory);
  }

  static MybatisOutboxStore store(DataSource dataSource) {
    return new MybatisOutboxStore(session(dataSource).getMapper(OutboxMapper.class));
  }
}

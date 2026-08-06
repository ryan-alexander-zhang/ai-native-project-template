package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

/**
 * Hand-wires this module's mappers over a plain {@link DataSource}, for the tests that exercise one
 * collaborator directly rather than booting an application context. A {@code
 * SpringManagedTransactionFactory} so a mapper call joins an ambient Spring transaction when there
 * is one, exactly as the auto-configured mappers do.
 */
final class WebStoreMappers {

  private WebStoreMappers() {}

  static SqlSessionTemplate session(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(IdempotencyMapper.class);
    configuration.addMapper(NonceMapper.class);
    configuration.addMapper(RateLimitMapper.class);
    configuration.addMapper(WebStoreSchemaMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory);
  }
}

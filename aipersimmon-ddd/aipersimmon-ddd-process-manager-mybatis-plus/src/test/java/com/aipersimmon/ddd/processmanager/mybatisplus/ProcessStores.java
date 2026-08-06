package com.aipersimmon.ddd.processmanager.mybatisplus;

import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure.MybatisProcessSchemaValidator;
import com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure.ProcessSchemaMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.MybatisProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.ProcessClaimMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessEffectStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessDeadlineMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessEffectMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessInstanceMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessRetentionMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessTransitionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

/**
 * The backend's stores and claim strategy over a plain {@link DataSource}, for the tests that drive
 * the engine directly instead of booting an application context.
 *
 * <p>A {@code SpringManagedTransactionFactory}, because almost every guarantee under test is a
 * transactional one: the engine's unit of work opens the transaction and every store call has to
 * enlist in it. A session with its own transactions would commit each write independently and
 * quietly turn the atomic-advance tests green for the wrong reason.
 */
final class ProcessStores {

  private final SqlSessionTemplate session;

  private ProcessStores(SqlSessionTemplate session) {
    this.session = session;
  }

  static ProcessStores over(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(ProcessInstanceMapper.class);
    configuration.addMapper(ProcessTransitionMapper.class);
    configuration.addMapper(ProcessEffectMapper.class);
    configuration.addMapper(ProcessDeadlineMapper.class);
    configuration.addMapper(ProcessRetentionMapper.class);
    configuration.addMapper(ProcessClaimMapper.class);
    configuration.addMapper(ProcessSchemaMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new ProcessStores(new SqlSessionTemplate(factory));
  }

  MybatisProcessInstanceStore instances() {
    return new MybatisProcessInstanceStore(session.getMapper(ProcessInstanceMapper.class));
  }

  MybatisProcessTransitionStore transitions() {
    return new MybatisProcessTransitionStore(session.getMapper(ProcessTransitionMapper.class));
  }

  MybatisProcessEffectStore effects() {
    return new MybatisProcessEffectStore(session.getMapper(ProcessEffectMapper.class));
  }

  MybatisProcessDeadlineStore deadlines() {
    return new MybatisProcessDeadlineStore(session.getMapper(ProcessDeadlineMapper.class));
  }

  MybatisProcessRetentionStore retention() {
    return new MybatisProcessRetentionStore(session.getMapper(ProcessRetentionMapper.class));
  }

  ProcessClaimMapper claimMapper() {
    return session.getMapper(ProcessClaimMapper.class);
  }

  MybatisProcessSchemaValidator schemaValidator() {
    return new MybatisProcessSchemaValidator(session.getMapper(ProcessSchemaMapper.class));
  }

  /**
   * The claim strategy for {@code dialect}. {@code skipLocked} follows the same mapping the
   * auto-configuration uses — true on PostgreSQL/MySQL, false on H2, which has no {@code SKIP
   * LOCKED} and falls back to the atomic conditional update.
   */
  MybatisProcessClaimStrategy claims(String dialect, WorkerId workerId) {
    return new MybatisProcessClaimStrategy(claimMapper(), dialect, !"h2".equals(dialect), workerId);
  }
}

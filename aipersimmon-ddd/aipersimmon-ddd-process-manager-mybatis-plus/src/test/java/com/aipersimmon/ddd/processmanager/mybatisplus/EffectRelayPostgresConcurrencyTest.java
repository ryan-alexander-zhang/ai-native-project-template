package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.engine.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.engine.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.MybatisProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.mybatisplus.lease.ProcessClaimMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessEffectStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessDeadlineMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessEffectMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessInstanceMapper;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.ProcessTransitionMapper;
import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The SKIP LOCKED claim gate on a real PostgreSQL, over the MyBatis-Plus backend: two workers
 * polling concurrently over many due effects must claim disjoint sets, so every effect is
 * dispatched exactly once. This proves the MyBatis {@code FOR UPDATE SKIP LOCKED} claim behaves
 * identically to the JDBC backend — the guarantee H2 cannot exercise.
 */
class EffectRelayPostgresConcurrencyTest {

  private static final PostgreSQLContainer<?> POSTGRES = SharedContainers.postgres();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private DefaultProcessRuntime runtime;
  private MybatisProcessEffectStore effectStore;
  private MybatisProcessInstanceStore instanceStore;
  private ProcessClaimMapper claimMapper;
  private SpringTxProcessUnitOfWork unitOfWork;
  private final ConcurrentBus bus = new ConcurrentBus();
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger tokens = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    DataSource ds = TestDataSources.from(POSTGRES);
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/postgresql/V1__aipersimmon_process_manager.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/postgresql/V2__drop_trace_id.sql"))
        .execute(ds);

    SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setDataSource(ds);
    Configuration configuration = new Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(ProcessInstanceMapper.class);
    configuration.addMapper(ProcessTransitionMapper.class);
    configuration.addMapper(ProcessEffectMapper.class);
    configuration.addMapper(ProcessDeadlineMapper.class);
    configuration.addMapper(ProcessClaimMapper.class);
    factory.setConfiguration(configuration);
    SqlSessionFactory sqlSessionFactory = factory.getObject();
    SqlSessionTemplate session = new SqlSessionTemplate(sqlSessionFactory);

    instanceStore = new MybatisProcessInstanceStore(session.getMapper(ProcessInstanceMapper.class));
    MybatisProcessTransitionStore transitionStore =
        new MybatisProcessTransitionStore(session.getMapper(ProcessTransitionMapper.class));
    effectStore = new MybatisProcessEffectStore(session.getMapper(ProcessEffectMapper.class));
    MybatisProcessDeadlineStore deadlineStore =
        new MybatisProcessDeadlineStore(session.getMapper(ProcessDeadlineMapper.class));
    claimMapper = session.getMapper(ProcessClaimMapper.class);
    unitOfWork = new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(ds));
    runtime =
        new DefaultProcessRuntime(
            instanceStore,
            transitionStore,
            effectStore,
            deadlineStore,
            new ProcessDefinitionRegistry(List.of(new MybatisTestProcess.Definition())),
            new ProcessPayloadCodecRegistry(
                List.of(MybatisTestProcess.beginCodec(), MybatisTestProcess.doThingCodec())),
            new ProcessStateCodecRegistry(List.of(MybatisTestProcess.stateCodec())),
            unitOfWork,
            CLOCK,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
  }

  @Test
  void twoWorkersClaimingConcurrentlyDeliverEachEffectExactlyOnce() throws InterruptedException {
    int total = 40;
    for (int i = 0; i < total; i++) {
      runtime.start(
          MybatisTestProcess.TYPE,
          new ProcessBusinessKey("order-" + i),
          new MybatisTestProcess.Begin("order-" + i),
          CommandContext.root("msg-" + i));
    }

    AtomicInteger delivered = new AtomicInteger();
    Thread a = new Thread(worker(delivered, total), "relay-A");
    Thread b = new Thread(worker(delivered, total), "relay-B");
    a.start();
    b.start();
    a.join(Duration.ofSeconds(30).toMillis());
    b.join(Duration.ofSeconds(30).toMillis());

    assertEquals(total, bus.messageIds.size(), "every effect dispatched exactly once");
    Set<String> distinct = new HashSet<>(bus.messageIds);
    assertEquals(
        total, distinct.size(), "no effect dispatched twice (SKIP LOCKED claimed disjoint sets)");
  }

  private Runnable worker(AtomicInteger delivered, int total) {
    return () -> {
      ProcessEffectRelay relay =
          new ProcessEffectRelay(
              new MybatisProcessClaimStrategy(
                  claimMapper,
                  "postgresql",
                  true,
                  new WorkerId("worker-" + Thread.currentThread().getName())),
              effectStore,
              instanceStore,
              new ProcessPayloadCodecRegistry(
                  List.of(MybatisTestProcess.beginCodec(), MybatisTestProcess.doThingCodec())),
              new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
              unitOfWork,
              zeroBackoff(),
              CLOCK,
              5,
              Duration.ofSeconds(30),
              () -> "lease-" + tokens.incrementAndGet());
      int idle = 0;
      while (delivered.get() < total && idle < 300) {
        int n = relay.pollOnce();
        if (n == 0) {
          idle++;
          try {
            Thread.sleep(3);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        } else {
          delivered.addAndGet(n);
          idle = 0;
        }
      }
    };
  }

  private static ProcessRetryPolicy zeroBackoff() {
    return new ProcessRetryPolicy() {
      @Override
      public Duration backoff(int attempt) {
        return Duration.ZERO;
      }

      @Override
      public int maxAttempts() {
        return 5;
      }
    };
  }

  static final class ConcurrentBus implements CommandBus {
    final ConcurrentLinkedQueue<String> messageIds = new ConcurrentLinkedQueue<>();

    @Override
    public <R> R send(Command<R> command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <R> R send(Command<R> command, CommandContext cause) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <R> R sendAs(Command<R> command, CommandContext messageContext) {
      messageIds.add(messageContext.messageId());
      return null;
    }
  }
}

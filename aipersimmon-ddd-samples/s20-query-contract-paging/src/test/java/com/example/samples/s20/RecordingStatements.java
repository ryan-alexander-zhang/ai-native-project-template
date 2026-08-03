package com.example.samples.s20;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * The instrument for the cost claims.
 *
 * <p>"A count is a second statement" and "a seek predicate needs no OFFSET" are assertions about SQL,
 * so the test reads the SQL. Contributed as a plain {@code InnerInterceptor} — the same seam the
 * production pagination interceptor uses — at an order beyond it, so what it records is the statement
 * as rewritten and actually sent, not the one the mapper started with.
 */
@TestConfiguration(proxyBeanMethods = false)
class RecordingStatements {

  static final class Log {
    private final List<String> statements = new CopyOnWriteArrayList<>();

    void record(String sql) {
      statements.add(sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
    }

    /** Every statement recorded against one table, in order. */
    List<String> touching(String table) {
      return statements.stream().filter(statement -> statement.contains(table)).toList();
    }

    void reset() {
      statements.clear();
    }
  }

  static final class Recorder implements InnerInterceptor {
    private final Log log;

    Recorder(Log log) {
      this.log = log;
    }

    @Override
    public void beforeQuery(
        Executor executor,
        MappedStatement ms,
        Object parameter,
        RowBounds rowBounds,
        ResultHandler resultHandler,
        BoundSql boundSql) {
      log.record(boundSql.getSql());
    }
  }

  @Bean
  Log statementLog() {
    return new Log();
  }

  @Bean
  @Order(500)
  InnerInterceptor statementRecorder(Log log) {
    return new Recorder(log);
  }
}

package com.example.samples.s02;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two containers: PostgreSQL for the aggregate, Redis for the edge store. Redis is not optional here
 * — {@code allow-in-memory-stores=false} means a context without it fails to start, which is the
 * posture a real deployment wants and the reason this base class imports both.
 */
@Import({PostgresServiceConnection.class, RedisServiceConnection.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class EdgeProtectionTestBase {

  @Autowired protected TestRestTemplate http;

  @Autowired protected JdbcTemplate jdbc;

  protected long orderCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s02_order", Long.class);
  }
}

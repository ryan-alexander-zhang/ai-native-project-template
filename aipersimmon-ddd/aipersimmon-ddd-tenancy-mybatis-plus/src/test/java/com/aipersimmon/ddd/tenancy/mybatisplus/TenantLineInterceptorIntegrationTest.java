package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the auto-configured {@link com.baomidou.mybatisplus.extension.plugins.inner
 * .TenantLineInnerInterceptor} rewrites real SQL: with {@code t18_thing} opted into {@code
 * tenant-tables}, a MyBatis-Plus {@code selectList} returns only the ambient tenant's rows, even
 * though rows for other tenants exist. Rows are seeded through a raw {@link JdbcTemplate} (not
 * intercepted) so the read-side filtering is what is under test.
 */
@SpringBootTest(classes = TenantLineInterceptorIntegrationTest.TestApp.class)
class TenantLineInterceptorIntegrationTest {

  @Autowired ThingMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM t18_thing");
    jdbc.update("INSERT INTO t18_thing(id, tenant_id, name) VALUES ('a1', 'acme', 'x')");
    jdbc.update("INSERT INTO t18_thing(id, tenant_id, name) VALUES ('a2', 'acme', 'y')");
    jdbc.update("INSERT INTO t18_thing(id, tenant_id, name) VALUES ('g1', 'globex', 'z')");
  }

  private int countFor(String tenant) {
    return TenantContext.runAs(Tenants.of(tenant), () -> mapper.selectList(null).size());
  }

  @Test
  void selectReturnsOnlyTheAmbientTenantsRows() {
    assertEquals(2, countFor("acme"));
    assertEquals(1, countFor("globex"));
    // A tenant with no rows sees nothing, though three rows exist across two other tenants.
    assertEquals(0, countFor("initech"));
  }

  @Test
  void withNoTenantBoundTheRootSentinelIsUsedAndMatchesNothing() {
    // No ambient tenant -> the handler falls back to the root sentinel; no __root__ rows exist.
    assertEquals(0, mapper.selectList(null).size());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @MapperScan("com.aipersimmon.ddd.tenancy.mybatisplus")
  static class TestApp {}
}

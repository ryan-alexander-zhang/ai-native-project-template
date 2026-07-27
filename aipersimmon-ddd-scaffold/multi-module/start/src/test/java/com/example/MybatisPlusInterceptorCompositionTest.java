package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Both inner interceptors are actually installed, and in the right order.
 *
 * <p>This guards the trap described in {@code design-00011} §3. MyBatis-Plus honours exactly one
 * {@link MybatisPlusInterceptor} bean, and two auto-configurations each registering one under
 * {@code @ConditionalOnMissingBean} do not compose — the second simply backs off, silently. Either
 * concern can therefore vanish without a single error:
 *
 * <ul>
 *   <li>lose {@link TenantLineInnerInterceptor} and tenants stop being isolated;
 *   <li>lose {@link OptimisticLockerInnerInterceptor} and {@code @Version} stops adding {@code
 *       WHERE version = ?}, so every {@code updateById} reports one row updated and the oversell of
 *       issue-00051 comes back while looking fixed.
 * </ul>
 *
 * <p>Asserting the composition directly is what makes that silence impossible to ship. It is the
 * assembly-level counterpart to {@code ConcurrentAggregateWriteTest} (behaviour) and {@code
 * TwoTenantAcceptanceTest} (isolation).
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class MybatisPlusInterceptorCompositionTest {

  @Autowired List<MybatisPlusInterceptor> interceptors;

  @Test
  void exactlyOneInterceptorIsRegistered() {
    assertEquals(
        1,
        interceptors.size(),
        "MyBatis-Plus honours a single MybatisPlusInterceptor; a second bean would be ignored");
  }

  @Test
  void itCarriesBothTenantLineAndOptimisticLockerInThatOrder() {
    List<InnerInterceptor> inner = interceptors.get(0).getInterceptors();

    assertTrue(
        inner.stream().anyMatch(TenantLineInnerInterceptor.class::isInstance),
        "tenant isolation must not be lost when the application owns the interceptor");
    assertTrue(
        inner.stream().anyMatch(OptimisticLockerInnerInterceptor.class::isInstance),
        "without the optimistic locker, @Version adds no WHERE predicate and oversell returns");

    int tenant = indexOf(inner, TenantLineInnerInterceptor.class);
    int locker = indexOf(inner, OptimisticLockerInnerInterceptor.class);
    assertTrue(tenant < locker, "MyBatis-Plus order: multi-tenant before optimistic lock");
  }

  private static int indexOf(List<InnerInterceptor> inner, Class<?> type) {
    for (int i = 0; i < inner.size(); i++) {
      if (type.isInstance(inner.get(i))) {
        return i;
      }
    }
    throw new AssertionError("no " + type.getSimpleName() + " installed");
  }
}

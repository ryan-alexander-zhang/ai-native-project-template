package com.aipersimmon.ddd.mybatisplus;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Owns the framework's single {@link MybatisPlusInterceptor} and composes every {@link
 * InnerInterceptor} bean into it, ordered.
 *
 * <p><strong>Why one owner.</strong> MyBatis-Plus honours exactly one {@code
 * MybatisPlusInterceptor} bean. Two auto-configurations that each register their own therefore do
 * not compose: under {@code @ConditionalOnMissingBean} whichever loses simply backs off, and its
 * concern vanishes with no error and no log. That failure mode is severe because the concerns are
 * security- and correctness-critical — losing the tenant-line interceptor stops isolating tenants,
 * and losing the optimistic locker stops {@code @Version} from adding its {@code WHERE version = ?}
 * predicate, so every update reports success and lost updates return. See {@code design-00011} §3.
 *
 * <p>Contributors therefore publish plain {@code InnerInterceptor} beans and let this class
 * assemble them. The framework's own contributions declare their position with {@code @Order},
 * following the order MyBatis-Plus documents — multi-tenant, then pagination, then optimistic lock:
 *
 * <ul>
 *   <li>{@code 100} — tenant line ({@code aipersimmon-ddd-tenancy-mybatis-plus})
 *   <li>{@code 200} — reserved for a consumer's pagination interceptor
 *   <li>{@code 300} — optimistic locker ({@code aipersimmon-ddd-persistence-mybatis-plus})
 * </ul>
 *
 * <p>An application that needs full control still declares its own {@code MybatisPlusInterceptor};
 * this one then backs off in whole. That escape hatch is deliberate — but note it takes over
 * assembly completely, so such an application must add the framework's inner interceptors itself.
 * The startup log below names what was installed, so a mistake there is visible rather than silent.
 */
@AutoConfiguration
@ConditionalOnClass({MybatisPlusInterceptor.class, SqlSessionFactory.class})
public class AipersimmonDddMybatisPlusAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddMybatisPlusAutoConfiguration.class);

  /**
   * The one interceptor, carrying every {@code InnerInterceptor} bean in {@code @Order} sequence.
   *
   * <p>Created eagerly rather than gated on {@code SqlSessionFactory}, so it exists before
   * MyBatis-Plus builds the session factory and is picked up. With no contributions it carries
   * nothing and rewrites nothing.
   */
  @Bean
  @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
  public MybatisPlusInterceptor aipersimmonMybatisPlusInterceptor(
      ObjectProvider<InnerInterceptor> innerInterceptors) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    List<InnerInterceptor> ordered = innerInterceptors.orderedStream().toList();
    ordered.forEach(interceptor::addInnerInterceptor);
    log.info(
        "aipersimmon-ddd MyBatis-Plus interceptors: {}",
        ordered.isEmpty()
            ? "none"
            : ordered.stream().map(each -> each.getClass().getSimpleName()).toList());
    return interceptor;
  }
}

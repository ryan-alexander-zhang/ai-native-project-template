package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The pagination interceptor, contributed as a plain bean at the position the library reserves for it.
 *
 * <p>Order 200 is not arbitrary: the framework documents 100 for the tenant line, 200 for a consumer's
 * pagination, 300 for the optimistic locker, and its own auto-configuration composes every {@code
 * InnerInterceptor} bean into the single {@code MybatisPlusInterceptor} MyBatis-Plus honours. Declaring an
 * interceptor this way rather than declaring a {@code MybatisPlusInterceptor} of one's own is what keeps the
 * framework's contributions installed — a consumer-declared one replaces the whole assembly, silently taking
 * the optimistic lock with it.
 */
@Configuration(proxyBeanMethods = false)
class PagingConfig {

  @Bean
  @Order(200)
  InnerInterceptor paginationInterceptor() {
    return new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
  }
}

package com.example.samples.s20.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.example.samples.s20.ordering.application.PageRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The pagination interceptor, contributed the way the framework asks for it.
 *
 * <p>MyBatis-Plus honours exactly one {@code MybatisPlusInterceptor} bean, so the library owns that
 * bean and assembles every {@code InnerInterceptor} bean into it in {@code @Order} sequence —
 * reserving {@code 100} for the tenant line, {@code 300} for the optimistic locker, and, in its own
 * words, "{@code 200} — reserved for a consumer's pagination interceptor". Declaring a plain {@code
 * InnerInterceptor} at that order is therefore the whole integration. Declaring a second {@code
 * MybatisPlusInterceptor} instead would make the library's back off, taking the {@code WHERE version
 * = ?} predicate with it — silently.
 */
@Configuration(proxyBeanMethods = false)
class PagingConfig {

  @Bean
  @Order(200)
  InnerInterceptor pagination() {
    PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
    // The interceptor's own ceiling, and it must sit above the contract's — a page of MAX_SIZE is
    // fetched as MAX_SIZE + 1 rows to learn whether another page exists, and this limit *clamps*
    // rather than refuses. Set it to MAX_SIZE and the largest allowed page would come back with its
    // extra row silently removed, reporting itself as the last page when it is not.
    pagination.setMaxLimit((long) PageRequest.MAX_SIZE + 1);
    return pagination;
  }
}

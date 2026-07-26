/**
 * The shared MyBatis-Plus seam: one {@link
 * com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor} owned here, assembled from
 * every {@code InnerInterceptor} bean the framework or the application contributes, in
 * {@code @Order} sequence.
 *
 * <p>It exists because MyBatis-Plus honours exactly one such bean, so components that each
 * registered their own would not compose — the loser of {@code @ConditionalOnMissingBean} would
 * back off with no error, silently dropping tenant isolation or the optimistic-lock predicate. See
 * {@code design-00011} §3.
 */
package com.aipersimmon.ddd.mybatisplus;

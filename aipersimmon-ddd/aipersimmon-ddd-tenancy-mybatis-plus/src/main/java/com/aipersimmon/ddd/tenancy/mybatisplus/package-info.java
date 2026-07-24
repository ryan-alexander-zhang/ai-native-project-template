/**
 * MyBatis-Plus enforcement for multi-tenancy.
 *
 * <p>Registers a {@link com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor} carrying
 * a {@link com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor} that
 * appends {@code tenant_id = <current tenant>} — read from the ambient {@link
 * com.aipersimmon.ddd.tenancy.TenantContext} — to statements on a configured set of tenant-scoped
 * tables.
 *
 * <p>The set is <em>empty by default</em> and opt-in per table, because the interceptor is global
 * across the single shared {@code SqlSessionFactory}. A blanket default would rewrite the
 * consumer's own domain tables and the framework's background-polled tables (which are read without
 * a bound tenant and would be wrongly narrowed to the root sentinel). Consumers list the domain
 * tables they want auto-scoped via {@code aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables}; the
 * framework's own tables are handled by their explicit persistence code and, on PostgreSQL, by RLS.
 */
package com.aipersimmon.ddd.tenancy.mybatisplus;

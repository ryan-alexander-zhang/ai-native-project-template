package com.aipersimmon.ddd.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Binds the named tenant to the test thread's {@link com.aipersimmon.ddd.tenancy.TenantContext}
 * before each test and clears it after — the {@code @AfterEach} cleanup every team otherwise writes
 * by hand, and forgets, until one test's tenant leaks into the next through the ThreadLocal
 * (issue-00140).
 *
 * <p>On the test class it applies to every test; on a method it overrides the class's value. The
 * value passes through {@code Tenants.fromValue} — the explicit trust-boundary reader — so a test
 * may bind any tenant a wire message could carry, including the root sentinel.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(WithTenantExtension.class)
public @interface WithTenant {

  /** The tenant id to bind, e.g. {@code "acme"}. */
  String value();
}

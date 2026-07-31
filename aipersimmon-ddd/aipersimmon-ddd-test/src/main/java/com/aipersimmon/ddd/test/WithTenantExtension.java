package com.aipersimmon.ddd.test;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The engine behind {@link WithTenant}: binds before each test, clears after each test —
 * unconditionally, so even a test that rebinds mid-flight cannot leak its last tenant into the next
 * test on the same worker thread.
 */
public final class WithTenantExtension implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    annotation(context)
        .ifPresent(withTenant -> TenantContext.set(Tenants.fromValue(withTenant.value())));
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TenantContext.clear();
  }

  /** The method's annotation wins over the class's, so one test can differ from its siblings. */
  private static Optional<WithTenant> annotation(ExtensionContext context) {
    Optional<WithTenant> onMethod =
        AnnotationSupport.findAnnotation(context.getTestMethod(), WithTenant.class);
    if (onMethod.isPresent()) {
      return onMethod;
    }
    return AnnotationSupport.findAnnotation(context.getTestClass(), WithTenant.class);
  }
}

package com.example;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Binds a tenant for the test thread, standing in for the edge filter.
 *
 * <p>These acceptance tests drive the application by calling the command bus directly, which skips
 * the web edge — and the edge is what binds the tenant in production. With multi-tenancy enabled
 * the framework refuses to run tenant-scoped work on an unbound thread rather than quietly using
 * the {@code __root__} sentinel, so a test that stands in for the edge has to do the edge's job.
 * Before that refusal existed these tests silently read and wrote the sentinel bucket, and nothing
 * in them said so.
 *
 * <p>Use it on classes whose subject is not tenancy itself. A test that asserts something *about*
 * tenants (two tenants not seeing each other, a tenant reaching the audit row) should call {@code
 * TenantContext.runAs} explicitly instead, so the tenant it operates as is visible at the
 * assertion.
 *
 * <p>For plain binding the framework now ships {@code com.aipersimmon.ddd.test.WithTenant}
 * (issue-00140) — a unit test that just needs a tenant uses that annotation and writes no
 * extension. This class remains because these acceptance tests need one more thing welded to the
 * same lifecycle: Awaitility must poll on the calling thread, or the binding would not reach the
 * tenant-scoped reads inside the poll.
 */
final class BoundTenant implements BeforeEachCallback, AfterEachCallback {

  /**
   * The tenant these flows run as: the seeded {@code demo} tenant, the same one the README
   * quickstart sends over HTTP — so the bus-driven and request-driven demo paths exercise the same
   * rows. Tests that own their fixtures (credit limits, stock levels) use {@code acme}/{@code
   * globex} instead and seed those themselves.
   */
  static final String TENANT = "demo";

  @Override
  public void beforeEach(ExtensionContext context) {
    TenantContext.set(Tenants.of(TENANT));
    // These flows settle asynchronously, so the assertions run inside Awaitility, which polls on
    // its
    // own thread by default — and a binding does not cross a thread hop. Polling on the calling
    // thread keeps the tenant in scope for the tenant-scoped reads those assertions make. (The
    // alternative, a tenant-propagating poll-thread factory, buys nothing here: nothing in these
    // conditions needs to be interruptible.)
    Awaitility.pollInSameThread();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    Awaitility.reset();
    TenantContext.clear();
  }
}

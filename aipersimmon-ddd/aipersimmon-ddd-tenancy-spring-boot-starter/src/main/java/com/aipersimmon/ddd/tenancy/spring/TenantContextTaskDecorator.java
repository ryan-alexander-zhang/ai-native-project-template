package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import java.util.Optional;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the current tenant across a thread hop, so work handed to an executor keeps the binding
 * the submitting request established.
 *
 * <p>The binding lives in a plain {@code ThreadLocal} and therefore does not follow {@code @Async}
 * methods, {@code CompletableFuture} callbacks, or anything else that runs on a pooled thread. This
 * decorator captures the tenant when the task is submitted and re-binds it around the task on the
 * worker thread, restoring the worker's previous state afterwards. A task submitted with no tenant
 * bound is left alone: inventing one would be the silent fallback this framework exists to avoid,
 * and {@link TenantContext#effective()} will fail loudly if that task does touch tenant-scoped
 * data.
 *
 * <p>Spring Boot applies a single {@link TaskDecorator} bean to the executor it auto-configures, so
 * registering this covers {@code @Async} and {@code TaskExecutor} injections out of the box. It
 * does not reach executors you build yourself, nor an executor configured with your own decorator —
 * in both cases compose this decorator into yours (or wrap the work in {@link
 * TenantContext#runAs(TenantId, Runnable)}) to keep propagation intact.
 */
public final class TenantContextTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable runnable) {
    Optional<TenantId> submitted = TenantContext.current();
    if (submitted.isEmpty()) {
      return runnable;
    }
    TenantId tenant = submitted.get();
    return () -> TenantContext.runAs(tenant, runnable);
  }
}

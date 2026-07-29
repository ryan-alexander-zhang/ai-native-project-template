package com.aipersimmon.ddd.tenancy.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.MissingTenantException;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTaskDecoratorTest {

  private final TenantContextTaskDecorator decorator = new TenantContextTaskDecorator();

  @AfterEach
  void tearDown() {
    TenantContext.clear();
    TenantContext.setRequired(false);
  }

  @Test
  void carriesTheSubmittingTenantOntoTheWorkerThread() throws Exception {
    TenantContext.setRequired(true);
    TenantContext.set(Tenants.of("acme"));
    AtomicReference<String> seen = new AtomicReference<>();

    Runnable decorated = decorator.decorate(() -> seen.set(TenantContext.effective().value()));
    runOnAnotherThread(decorated);

    assertEquals("acme", seen.get());
  }

  @Test
  void leavesTheWorkerUnboundWhenNothingWasBoundAtSubmission() throws Exception {
    TenantContext.setRequired(true);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Runnable decorated =
        decorator.decorate(
            () ->
                failure.set(assertThrows(MissingTenantException.class, TenantContext::effective)));
    runOnAnotherThread(decorated);

    assertTrue(
        failure.get() instanceof MissingTenantException,
        "an undecorated hop must fail loudly rather than inherit a sentinel");
  }

  @Test
  void leavesNoBindingBehindOnThePooledThread() throws Exception {
    TenantContext.set(Tenants.of("acme"));
    Runnable decorated = decorator.decorate(() -> {});

    AtomicReference<String> nextTaskSaw = new AtomicReference<>("not run");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(decorated).get();
      // The same pooled thread serves the next task, which may belong to another tenant entirely.
      executor
          .submit(() -> nextTaskSaw.set(TenantContext.current().map(t -> t.value()).orElse(null)))
          .get();
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertNull(nextTaskSaw.get(), "the binding must not outlive the task it was created for");
  }

  private static void runOnAnotherThread(Runnable work) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(work).get();
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}

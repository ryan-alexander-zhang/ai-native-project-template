package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Covers the default methods of the CQRS SPI interfaces. */
class CqrsDefaultsTest {

  @Test
  void unitOfWorkRunnableOverloadRunsTheWorkInsideTheSupplierForm() {
    List<String> trace = new ArrayList<>();
    UnitOfWork uow =
        new UnitOfWork() {
          @Override
          public <R> R execute(Supplier<R> work) {
            trace.add("begin");
            R result = work.get();
            trace.add("commit");
            return result;
          }
        };

    // An explicit Runnable forces the default execute(Runnable) overload (a bare
    // expression lambda would instead bind to the Supplier form).
    Runnable work = () -> trace.add("work");
    uow.execute(work);

    assertEquals(List.of("begin", "work", "commit"), trace);
  }

  @Test
  void commandInterceptorOrderDefaultsToZero() {
    CommandInterceptor interceptor =
        new CommandInterceptor() {
          @Override
          public <R> R intercept(
              Command<R> command, CommandContext context, Invocation<R> invocation) {
            return invocation.proceed();
          }
        };

    assertEquals(0, interceptor.order());
  }

  @Test
  void commandInterceptorProceedRunsTheContinuation() {
    CommandInterceptor.Invocation<String> invocation = () -> "done";
    assertTrue(invocation.proceed().equals("done"));
  }
}

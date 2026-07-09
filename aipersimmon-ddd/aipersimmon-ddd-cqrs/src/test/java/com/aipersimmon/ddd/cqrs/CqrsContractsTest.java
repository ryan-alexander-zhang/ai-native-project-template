package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the CQRS contracts compose type-safely and that the interceptor SPI
 * has the intended around-and-ordering semantics, using a minimal in-test bus.
 * The shipped Spring bus is exercised separately in the starter module.
 */
class CqrsContractsTest {

    /** A command whose result type is carried in the type parameter. */
    record CreateThing(String name) implements Command<String> {
    }

    @Test
    void dispatchesToHandlerAndReturnsTypedResult() {
        CommandHandler<CreateThing, String> handler = c -> "created:" + c.name();
        CommandBus bus = new TestBus(handler, List.of());

        String result = bus.send(new CreateThing("widget"));

        assertEquals("created:widget", result);
    }

    @Test
    void interceptorsRunOutsideInByOrderAroundTheHandler() {
        List<String> trace = new ArrayList<>();
        CommandHandler<CreateThing, String> handler = c -> {
            trace.add("handle");
            return c.name();
        };
        CommandInterceptor outer = tracing("outer", 0, trace);
        CommandInterceptor inner = tracing("inner", 100, trace);

        // Registered out of order to prove the bus sorts by order().
        CommandBus bus = new TestBus(handler, List.of(inner, outer));
        bus.send(new CreateThing("x"));

        assertEquals(
                List.of("outer>", "inner>", "handle", "inner<", "outer<"),
                trace);
    }

    @Test
    void unitOfWorkRunnableOverloadDelegatesToSupplierForm() {
        List<String> trace = new ArrayList<>();
        UnitOfWork uow = new UnitOfWork() {
            @Override
            public <R> R execute(java.util.function.Supplier<R> work) {
                trace.add("begin");
                R r = work.get();
                trace.add("commit");
                return r;
            }
        };

        uow.execute(() -> trace.add("work"));

        assertEquals(List.of("begin", "work", "commit"), trace);
    }

    private static CommandInterceptor tracing(String name, int order, List<String> trace) {
        return new CommandInterceptor() {
            @Override
            public <R> R intercept(Command<R> command, Invocation<R> invocation) {
                trace.add(name + ">");
                try {
                    return invocation.proceed();
                } finally {
                    trace.add(name + "<");
                }
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    /** Minimal single-handler bus that folds the interceptor chain, lowest order outermost. */
    private static final class TestBus implements CommandBus {
        private final CommandHandler<CreateThing, String> handler;
        private final List<CommandInterceptor> interceptors;

        TestBus(CommandHandler<CreateThing, String> handler, List<CommandInterceptor> interceptors) {
            this.handler = handler;
            this.interceptors = interceptors.stream()
                    .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                    .toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> R send(Command<R> command) {
            CommandInterceptor.Invocation<R> invocation =
                    () -> (R) handler.handle((CreateThing) command);
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                CommandInterceptor interceptor = interceptors.get(i);
                CommandInterceptor.Invocation<R> next = invocation;
                invocation = () -> interceptor.intercept(command, next);
            }
            return invocation.proceed();
        }
    }
}

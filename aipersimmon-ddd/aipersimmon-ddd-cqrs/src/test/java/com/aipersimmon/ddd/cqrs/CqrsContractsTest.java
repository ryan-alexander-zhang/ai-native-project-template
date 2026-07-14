package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the CQRS contracts compose type-safely, that the {@link CommandContext}
 * causal chain derives correctly, and that the interceptor SPI has the intended
 * around-and-ordering semantics, using a minimal in-test bus. The shipped Spring
 * bus is exercised separately in the starter module.
 */
class CqrsContractsTest {

    /** A command whose result type is carried in the type parameter. */
    record CreateThing(String name) implements Command<String> {
    }

    record ThingImported(String id) implements IntegrationEvent {
    }

    @Test
    void ofEnvelopeMakesTheInboundEventTheCause() {
        EventEnvelope<ThingImported> envelope = new EventEnvelope<>(
                "evt-9", "/test", "ThingImported", 1, Instant.EPOCH,
                "subj-1", "corr-3", "upstream-cause", "trace-y", new ThingImported("t-1"));

        CommandContext cause = CommandContext.of(envelope);
        assertEquals("evt-9", cause.messageId(), "the event's id becomes the cause's message id");
        assertEquals("corr-3", cause.correlationId());
        assertEquals("trace-y", cause.traceId());

        // A command dispatched from this cause records the event as its causation.
        CommandContext command = cause.deriveChild("cmd-1");
        assertEquals("corr-3", command.correlationId());
        assertEquals("evt-9", command.causationId());
    }

    @Test
    void dispatchesToHandlerAndReturnsTypedResult() {
        CommandHandler<CreateThing, String> handler = (c, ctx) -> "created:" + c.name();
        CommandBus bus = new TestBus(handler, List.of());

        String result = bus.send(new CreateThing("widget"));

        assertEquals("created:widget", result);
    }

    @Test
    void rootContextSeedsCorrelationToItsOwnIdWithNoCausation() {
        List<CommandContext> seen = new ArrayList<>();
        CommandBus bus = new TestBus((c, ctx) -> {
            seen.add(ctx);
            return c.name();
        }, List.of());

        bus.send(new CreateThing("x"));

        CommandContext ctx = seen.get(0);
        assertEquals(ctx.messageId(), ctx.correlationId());
        assertNull(ctx.causationId());
    }

    @Test
    void causedCommandInheritsCorrelationAndTraceAndRecordsItsCauser() {
        List<CommandContext> seen = new ArrayList<>();
        CommandBus bus = new TestBus((c, ctx) -> {
            seen.add(ctx);
            return c.name();
        }, List.of());

        // e.g. an inbound integration event mapped to a cause context.
        CommandContext cause = CommandContext.root("evt-1", "trace-9");
        bus.send(new CreateThing("y"), cause);

        CommandContext ctx = seen.get(0);
        assertEquals("evt-1", ctx.correlationId(), "inherits the cause's correlation");
        assertEquals("evt-1", ctx.causationId(), "records the cause as its causation");
        assertEquals("trace-9", ctx.traceId(), "propagates the trace");
    }

    @Test
    void interceptorsRunOutsideInByOrderAroundTheHandler() {
        List<String> trace = new ArrayList<>();
        CommandHandler<CreateThing, String> handler = (c, ctx) -> {
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
            public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
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
        private final AtomicInteger ids = new AtomicInteger();

        TestBus(CommandHandler<CreateThing, String> handler, List<CommandInterceptor> interceptors) {
            this.handler = handler;
            this.interceptors = interceptors.stream()
                    .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                    .toList();
        }

        @Override
        public <R> R send(Command<R> command) {
            return dispatch(command, CommandContext.root(nextId(), null));
        }

        @Override
        public <R> R send(Command<R> command, CommandContext cause) {
            return dispatch(command, cause.deriveChild(nextId()));
        }

        @SuppressWarnings("unchecked")
        private <R> R dispatch(Command<R> command, CommandContext context) {
            CommandInterceptor.Invocation<R> invocation =
                    () -> (R) handler.handle((CreateThing) command, context);
            for (int i = interceptors.size() - 1; i >= 0; i--) {
                CommandInterceptor interceptor = interceptors.get(i);
                CommandInterceptor.Invocation<R> next = invocation;
                invocation = () -> interceptor.intercept(command, context, next);
            }
            return invocation.proceed();
        }

        private String nextId() {
            return "cmd-" + ids.incrementAndGet();
        }
    }
}

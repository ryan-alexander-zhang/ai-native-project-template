package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link RegistryCommandBus#sendAs(Command, CommandContext)} must dispatch under the
 * caller-supplied context <em>verbatim</em>: no id is minted and no child is derived,
 * so the same persisted effect redelivered by a relay reaches the handler under the
 * same messageId. This is the staged-dispatch contract.
 */
class RegistryCommandBusSendAsTest {

    record Reserve(String sku) implements Command<String> {
    }

    /** Concrete handler so RegistryCommandBus can resolve the command type from the signature. */
    static final class CapturingReserveHandler implements CommandHandler<Reserve, String> {
        final List<CommandContext> seen = new ArrayList<>();

        @Override
        public String handle(Reserve command, CommandContext context) {
            seen.add(context);
            return "ok:" + command.sku();
        }
    }

    @Test
    void sendAsUsesTheGivenContextVerbatimAndMintsNoId() {
        CapturingReserveHandler handler = new CapturingReserveHandler();
        AtomicInteger idCalls = new AtomicInteger();
        CommandBus bus = new RegistryCommandBus(
                List.of(handler), List.of(), () -> "MINTED-" + idCalls.incrementAndGet());

        // effectId is the durable identity the relay reconstructs into a full context.
        CommandContext effectCtx = new CommandContext("effect-42", "corr-7", "input-msg-3");
        String result = bus.sendAs(new Reserve("sku-1"), effectCtx);

        assertEquals("ok:sku-1", result);
        assertEquals(1, handler.seen.size());
        CommandContext delivered = handler.seen.get(0);
        assertEquals("effect-42", delivered.messageId(), "messageId must equal effectId, verbatim");
        assertEquals("corr-7", delivered.correlationId());
        assertEquals("input-msg-3", delivered.causationId());
        assertEquals(0, idCalls.get(), "sendAs must not mint an id");
    }

    @Test
    void redeliveringTheSameEffectKeepsTheSameMessageId() {
        CapturingReserveHandler handler = new CapturingReserveHandler();
        CommandBus bus = new RegistryCommandBus(List.of(handler), List.of());
        CommandContext effectCtx = CommandContext.root("effect-99");

        bus.sendAs(new Reserve("s"), effectCtx);
        bus.sendAs(new Reserve("s"), effectCtx); // relay redelivers the same persisted effect

        assertEquals(List.of("effect-99", "effect-99"),
                handler.seen.stream().map(CommandContext::messageId).toList(),
                "at-least-once redelivery must reach the handler under one stable id");
    }

    @Test
    void sendStillMintsAFreshIdSoTheTwoPathsStayDistinct() {
        CapturingReserveHandler handler = new CapturingReserveHandler();
        CommandBus bus = new RegistryCommandBus(
                List.of(handler), List.of(), () -> "MINTED-1");

        bus.send(new Reserve("s"));

        CommandContext ctx = handler.seen.get(0);
        assertEquals("MINTED-1", ctx.messageId(), "send mints via the id generator");
        assertEquals("MINTED-1", ctx.correlationId(), "a root send seeds correlation to its own id");
        assertNull(ctx.causationId());
    }
}

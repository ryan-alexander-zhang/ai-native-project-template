package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcess;
import org.springframework.stereotype.Component;

/**
 * Drives the order-fulfilment {@link OrderFulfilmentProcess} through the durable
 * {@link ProcessRuntime}: {@code placed} starts the instance; each subsequent fact resolves the
 * instance's {@link ProcessRef} from its order id (the business key) and hands the input to
 * {@code handle}. The runtime stages the ordering commands as effects and a relay delivers them —
 * so the coordination is durable and at-least-once, not a synchronous in-memory saga.
 *
 * <p>The terminal domain facts (confirmed/cancelled) arrive without an inbound message, so they run
 * under a deterministic root context keyed by the order id; the cross-context result facts carry the
 * triggering event's context, keeping the causal chain intact.
 */
@Component
public class RuntimeOrderFulfilmentProcess implements OrderFulfilmentProcess {

    private static final ProcessType TYPE = OrderFulfilmentDefinition.PROCESS_TYPE;

    private final ProcessRuntime runtime;
    private final JdbcProcessQuery query;

    public RuntimeOrderFulfilmentProcess(ProcessRuntime runtime, JdbcProcessQuery query) {
        this.runtime = runtime;
        this.query = query;
    }

    @Override
    public void placed(String orderId) {
        runtime.start(TYPE, new ProcessBusinessKey(orderId),
                new OrderFulfilmentInput.OrderPlaced(orderId), rootContext("placed", orderId));
    }

    @Override
    public void stockReserved(String orderId, String reservationId, CommandContext cause) {
        handle(orderId, new OrderFulfilmentInput.StockReserved(orderId, reservationId), cause);
    }

    @Override
    public void stockReservationFailed(String orderId, String code, String reason, CommandContext cause) {
        handle(orderId, new OrderFulfilmentInput.StockReservationFailed(orderId, code, reason), cause);
    }

    @Override
    public void paymentAuthorized(String orderId, CommandContext cause) {
        handle(orderId, new OrderFulfilmentInput.PaymentAuthorized(orderId), cause);
    }

    @Override
    public void paymentDeclined(String orderId, String code, String reason, CommandContext cause) {
        handle(orderId, new OrderFulfilmentInput.PaymentDeclined(orderId, code, reason), cause);
    }

    @Override
    public void stockReleased(String orderId, String reservationId, CommandContext cause) {
        handle(orderId, new OrderFulfilmentInput.StockReleased(orderId, reservationId), cause);
    }

    @Override
    public void orderConfirmed(String orderId) {
        handle(orderId, new OrderFulfilmentInput.OrderConfirmed(orderId), rootContext("confirmed", orderId));
    }

    @Override
    public void orderCancelled(String orderId) {
        handle(orderId, new OrderFulfilmentInput.OrderCancelled(orderId), rootContext("cancelled", orderId));
    }

    private void handle(String orderId, ProcessInput input, CommandContext cause) {
        ProcessRef ref = query.findRef(TYPE, new ProcessBusinessKey(orderId))
                .orElseThrow(() -> new IllegalStateException(
                        "no order-fulfilment instance for order " + orderId));
        runtime.handle(ref, input, cause);
    }

    private static CommandContext rootContext(String fact, String orderId) {
        return CommandContext.root(fact + ":" + orderId);
    }
}

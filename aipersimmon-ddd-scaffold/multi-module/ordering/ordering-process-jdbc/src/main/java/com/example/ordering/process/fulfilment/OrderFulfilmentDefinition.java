package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.fulfilment.OrderFulfilmentState.Step;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.StockReleaseRef;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment coordination policy as a pure, deterministic {@link ProcessDefinition} — the
 * durable process-manager replacement for the old orchestration saga. Given the current state and an
 * input, it returns the next state, lifecycle, and the ordering commands to dispatch; the durable
 * runtime persists the transition and relays the commands at-least-once.
 *
 * <p>The flow, and the two properties that keep it honest:
 * <pre>
 *   OrderPlaced ─▶ AWAITING_STOCK
 *     StockReserved ─▶ RequestPayment ─▶ AWAITING_PAYMENT
 *       PaymentAuthorized ─▶ ConfirmOrder ─▶ AWAITING_ORDER_CONFIRMATION
 *         OrderConfirmed ─▶ COMPLETED (ORDER_CONFIRMED)
 *       PaymentDeclined  ─▶ COMPENSATING, RequestStockRelease ─▶ AWAITING_STOCK_RELEASE
 *         StockReleased  ─▶ CancelOrder(PaymentDeclinedAfterStockReleased) ─▶ AWAITING_ORDER_CANCELLATION
 *           OrderCancelled ─▶ COMPLETED (ORDER_CANCELLED)
 *     StockReservationFailed ─▶ COMPENSATING, CancelOrder(InventoryUnavailable) ─▶ AWAITING_ORDER_CANCELLATION
 * </pre>
 * It reaches a terminal lifecycle only on {@code OrderConfirmed}/{@code OrderCancelled} — the actual
 * outcome — never when a confirm/cancel command is merely dispatched. And compensation is ordered:
 * a payment decline goes through stock release before cancellation, so the evidence-bearing
 * {@link CancellationReason.PaymentDeclinedAfterStockReleased} can only be built once the stock the
 * order held was actually released.
 */
@Component
public class OrderFulfilmentDefinition implements ProcessDefinition<OrderFulfilmentState> {

    public static final ProcessType PROCESS_TYPE = new ProcessType("ordering.fulfilment");

    @Override
    public ProcessType processType() {
        return PROCESS_TYPE;
    }

    @Override
    public DefinitionVersion definitionVersion() {
        return new DefinitionVersion("v1");
    }

    @Override
    public boolean activeForNewInstances() {
        return true;
    }

    @Override
    public StateSchemaVersion stateSchemaVersion() {
        return new StateSchemaVersion(1);
    }

    @Override
    public ProcessDecision<OrderFulfilmentState> start(ProcessInput input, ProcessContext context) {
        if (input instanceof OrderFulfilmentInput.OrderPlaced placed) {
            OrderFulfilmentState state =
                    new OrderFulfilmentState(placed.orderId(), Step.AWAITING_STOCK, null, null);
            return running(state, Step.AWAITING_STOCK, "order-placed");
        }
        throw new IllegalStateException("unexpected start input: " + input);
    }

    @Override
    public ProcessDecision<OrderFulfilmentState> react(
            OrderFulfilmentState state, ProcessInput input, ProcessContext context) {
        OrderFulfilmentInput in = (OrderFulfilmentInput) input;
        String orderId = state.orderId();
        return switch (in) {
            case OrderFulfilmentInput.StockReserved reserved -> running(
                    state.reserved(reserved.reservationId(), Step.AWAITING_PAYMENT), Step.AWAITING_PAYMENT,
                    "stock-reserved", new DispatchCommand(new RequestPayment(orderId)));
            case OrderFulfilmentInput.StockReservationFailed failed -> {
                ReservationFailureRef failure = new ReservationFailureRef(
                        orderId, new OrderId(orderId), failed.code(), failed.reason());
                yield compensating(
                        state.withStep(Step.AWAITING_ORDER_CANCELLATION), Step.AWAITING_ORDER_CANCELLATION,
                        "stock-reservation-failed",
                        new DispatchCommand(new CancelOrder(orderId, new CancellationReason.InventoryUnavailable(failure))));
            }
            case OrderFulfilmentInput.PaymentAuthorized ignored -> running(
                    state.withStep(Step.AWAITING_ORDER_CONFIRMATION), Step.AWAITING_ORDER_CONFIRMATION,
                    "payment-authorized", new DispatchCommand(new ConfirmOrder(orderId)));
            case OrderFulfilmentInput.PaymentDeclined declined -> compensating(
                    state.declined(declined.code(), Step.AWAITING_STOCK_RELEASE), Step.AWAITING_STOCK_RELEASE,
                    "payment-declined", new DispatchCommand(new RequestStockRelease(orderId, state.reservationId())));
            case OrderFulfilmentInput.StockReleased released -> {
                OrderId id = new OrderId(orderId);
                CancellationReason reason = new CancellationReason.PaymentDeclinedAfterStockReleased(
                        new PaymentDeclineRef(orderId, id, state.paymentDeclineCode()),
                        new StockReleaseRef(released.reservationId(), id));
                yield compensating(
                        state.withStep(Step.AWAITING_ORDER_CANCELLATION), Step.AWAITING_ORDER_CANCELLATION,
                        "stock-released", new DispatchCommand(new CancelOrder(orderId, reason)));
            }
            case OrderFulfilmentInput.OrderConfirmed ignored ->
                    completed(state.withStep(Step.CONFIRMED), Step.CONFIRMED, "order-confirmed", "ORDER_CONFIRMED");
            case OrderFulfilmentInput.OrderCancelled ignored ->
                    completed(state.withStep(Step.CANCELLED), Step.CANCELLED, "order-cancelled", "ORDER_CANCELLED");
            case OrderFulfilmentInput.OrderPlaced ignored ->
                    throw new IllegalStateException("order " + orderId + " already started");
        };
    }

    private static ProcessDecision<OrderFulfilmentState> running(
            OrderFulfilmentState state, Step step, String code, ProcessEffect... effects) {
        return decision(state, ProcessLifecycle.RUNNING, step, Optional.empty(), code, effects);
    }

    private static ProcessDecision<OrderFulfilmentState> compensating(
            OrderFulfilmentState state, Step step, String code, ProcessEffect... effects) {
        return decision(state, ProcessLifecycle.COMPENSATING, step, Optional.empty(), code, effects);
    }

    private static ProcessDecision<OrderFulfilmentState> completed(
            OrderFulfilmentState state, Step step, String code, String outcome) {
        return decision(state, ProcessLifecycle.COMPLETED, step, Optional.of(new ProcessOutcome(outcome)), code);
    }

    private static ProcessDecision<OrderFulfilmentState> decision(
            OrderFulfilmentState state, ProcessLifecycle lifecycle, Step step,
            Optional<ProcessOutcome> outcome, String code, ProcessEffect... effects) {
        return new ProcessDecision<>(state, lifecycle, new ProcessStep(step.name()), outcome,
                new DecisionCode(code), List.of(effects));
    }
}

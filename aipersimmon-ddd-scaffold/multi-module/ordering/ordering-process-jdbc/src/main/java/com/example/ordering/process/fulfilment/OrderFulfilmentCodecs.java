package com.example.ordering.infrastructure.process;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.fulfilment.OrderFulfilmentDefinition;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.OrderCancelled;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.OrderConfirmed;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.OrderPlaced;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.PaymentAuthorized;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.PaymentDeclined;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.StockReleased;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.StockReservationFailed;
import com.example.ordering.application.fulfilment.OrderFulfilmentInput.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentState;
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
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit process-manager codecs for the order-fulfilment flow (the provider side): stable logical
 * type/version for each persisted input, command effect, and the flow state — never a Java class
 * name. Payloads are encoded as unit-separator-delimited UTF-8, so any field is round-tripped without
 * a JSON dependency; {@link CancelOrder} additionally carries the evidence-bearing
 * {@link CancellationReason} the ordering aggregate requires. The starter collects these beans into
 * its codec registries at startup.
 */
@Configuration
public class OrderFulfilmentCodecs {

    private static final String US = "\u001f";

    // ----- state -----
    @Bean
    ProcessStateCodec<OrderFulfilmentState> orderFulfilmentStateCodec() {
        return new ProcessStateCodec<>() {
            @Override
            public ProcessType processType() {
                return OrderFulfilmentDefinition.PROCESS_TYPE;
            }

            @Override
            public StateSchemaVersion schemaVersion() {
                return new StateSchemaVersion(1);
            }

            @Override
            public EncodedPayload encode(OrderFulfilmentState s) {
                return bytes(new PayloadType("ordering.fulfilment.state", 1), String.join(US,
                        s.orderId(), s.step().name(), nz(s.reservationId()), nz(s.paymentDeclineCode())));
            }

            @Override
            public OrderFulfilmentState decode(EncodedPayload p) {
                String[] f = parts(p);
                return new OrderFulfilmentState(f[0], Step.valueOf(f[1]), blankToNull(f[2]), blankToNull(f[3]));
            }
        };
    }

    // ----- inputs -----
    @Bean
    ProcessPayloadCodec<OrderPlaced> orderPlacedCodec() {
        return codec("ordering.fulfilment.order-placed", OrderPlaced.class,
                OrderPlaced::orderId, OrderPlaced::new);
    }

    @Bean
    ProcessPayloadCodec<StockReserved> stockReservedCodec() {
        return codec("ordering.fulfilment.stock-reserved", StockReserved.class,
                v -> String.join(US, v.orderId(), v.reservationId()),
                s -> new StockReserved(parts(s)[0], parts(s)[1]));
    }

    @Bean
    ProcessPayloadCodec<StockReservationFailed> stockReservationFailedCodec() {
        return codec("ordering.fulfilment.stock-reservation-failed", StockReservationFailed.class,
                v -> String.join(US, v.orderId(), v.code(), v.reason()),
                s -> new StockReservationFailed(parts(s)[0], parts(s)[1], parts(s)[2]));
    }

    @Bean
    ProcessPayloadCodec<PaymentAuthorized> paymentAuthorizedCodec() {
        return codec("ordering.fulfilment.payment-authorized", PaymentAuthorized.class,
                PaymentAuthorized::orderId, PaymentAuthorized::new);
    }

    @Bean
    ProcessPayloadCodec<PaymentDeclined> paymentDeclinedCodec() {
        return codec("ordering.fulfilment.payment-declined", PaymentDeclined.class,
                v -> String.join(US, v.orderId(), v.code(), v.reason()),
                s -> new PaymentDeclined(parts(s)[0], parts(s)[1], parts(s)[2]));
    }

    @Bean
    ProcessPayloadCodec<StockReleased> stockReleasedCodec() {
        return codec("ordering.fulfilment.stock-released", StockReleased.class,
                v -> String.join(US, v.orderId(), v.reservationId()),
                s -> new StockReleased(parts(s)[0], parts(s)[1]));
    }

    @Bean
    ProcessPayloadCodec<OrderConfirmed> orderConfirmedCodec() {
        return codec("ordering.fulfilment.order-confirmed", OrderConfirmed.class,
                OrderConfirmed::orderId, OrderConfirmed::new);
    }

    @Bean
    ProcessPayloadCodec<OrderCancelled> orderCancelledCodec() {
        return codec("ordering.fulfilment.order-cancelled", OrderCancelled.class,
                OrderCancelled::orderId, OrderCancelled::new);
    }

    // ----- command effects -----
    @Bean
    ProcessPayloadCodec<RequestPayment> requestPaymentCodec() {
        return codec("ordering.fulfilment.request-payment", RequestPayment.class,
                RequestPayment::orderId, RequestPayment::new);
    }

    @Bean
    ProcessPayloadCodec<ConfirmOrder> confirmOrderCodec() {
        return codec("ordering.fulfilment.confirm-order", ConfirmOrder.class,
                ConfirmOrder::orderId, ConfirmOrder::new);
    }

    @Bean
    ProcessPayloadCodec<RequestStockRelease> requestStockReleaseCodec() {
        return codec("ordering.fulfilment.request-stock-release", RequestStockRelease.class,
                v -> String.join(US, v.orderId(), v.reservationId()),
                s -> new RequestStockRelease(parts(s)[0], parts(s)[1]));
    }

    @Bean
    ProcessPayloadCodec<CancelOrder> cancelOrderCodec() {
        return codec("ordering.fulfilment.cancel-order", CancelOrder.class,
                OrderFulfilmentCodecs::encodeCancel, OrderFulfilmentCodecs::decodeCancel);
    }

    private static String encodeCancel(CancelOrder c) {
        return switch (c.reason()) {
            case CancellationReason.InventoryUnavailable u -> String.join(US,
                    c.orderId(), "INVENTORY_UNAVAILABLE", u.failure().failureId(), u.failure().orderId().value(),
                    u.failure().reasonCode(), u.failure().detail());
            case CancellationReason.PaymentDeclinedAfterStockReleased d -> String.join(US,
                    c.orderId(), "PAYMENT_DECLINED", d.paymentDecline().declineId(),
                    d.paymentDecline().orderId().value(), d.paymentDecline().declineCode(),
                    d.stockRelease().releaseId(), d.stockRelease().orderId().value());
            default -> throw new ProcessSerializationException(
                    "order-fulfilment does not dispatch CancelOrder with reason " + c.reason());
        };
    }

    private static CancelOrder decodeCancel(String s) {
        String[] f = s.split(US, -1);
        CancellationReason reason = switch (f[1]) {
            case "INVENTORY_UNAVAILABLE" -> new CancellationReason.InventoryUnavailable(
                    new ReservationFailureRef(f[2], new OrderId(f[3]), f[4], f[5]));
            case "PAYMENT_DECLINED" -> new CancellationReason.PaymentDeclinedAfterStockReleased(
                    new PaymentDeclineRef(f[2], new OrderId(f[3]), f[4]),
                    new StockReleaseRef(f[5], new OrderId(f[6])));
            default -> throw new ProcessSerializationException("unknown cancel reason kind: " + f[1]);
        };
        return new CancelOrder(f[0], reason);
    }

    private static <T> ProcessPayloadCodec<T> codec(
            String logicalType, Class<T> javaType, Function<T, String> encode, Function<String, T> decode) {
        return new ProcessPayloadCodec<>() {
            @Override
            public PayloadType payloadType() {
                return new PayloadType(logicalType, 1);
            }

            @Override
            public Class<T> javaType() {
                return javaType;
            }

            @Override
            public EncodedPayload encode(T value) {
                return bytes(payloadType(), encode.apply(value));
            }

            @Override
            public T decode(EncodedPayload payload) {
                return decode.apply(new String(payload.data(), StandardCharsets.UTF_8));
            }
        };
    }

    private static EncodedPayload bytes(PayloadType type, String text) {
        return new EncodedPayload(type, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] parts(EncodedPayload payload) {
        return new String(payload.data(), StandardCharsets.UTF_8).split(US, -1);
    }

    private static String[] parts(String text) {
        return text.split(US, -1);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return v.isEmpty() ? null : v;
    }
}

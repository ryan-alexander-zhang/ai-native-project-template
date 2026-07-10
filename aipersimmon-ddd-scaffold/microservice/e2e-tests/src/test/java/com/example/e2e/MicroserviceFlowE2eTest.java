package com.example.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.inventory.InventoryServiceApplication;
import com.example.ordering.OrderingServiceApplication;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Boots both services in one JVM against an embedded Kafka broker and drives a real
 * order through the broker. It asserts the two cross-service outcomes: stock
 * reserved confirms the order, and a reservation failure compensates by cancelling
 * it. The whole path runs over Kafka — ordering's outbox to inventory's inbox and
 * back — with each service in its own consumer group and its own database.
 */
class MicroserviceFlowE2eTest {

    private static final String TOPIC = "shop.integration-events";

    private static EmbeddedKafkaBroker broker;
    private static ConfigurableApplicationContext ordering;
    private static ConfigurableApplicationContext inventory;

    private static CommandBus commandBus;
    private static QueryBus queryBus;

    @BeforeAll
    static void startEverything() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();
        String brokers = broker.getBrokersAsString();

        // Distinct spring.config.name per service: both service jars are on this
        // module's classpath and would otherwise collide on application.properties.
        ordering = boot(OrderingServiceApplication.class, "ordering-e2e", brokers);
        inventory = boot(InventoryServiceApplication.class, "inventory-e2e", brokers);

        commandBus = ordering.getBean(CommandBus.class);
        queryBus = ordering.getBean(QueryBus.class);
    }

    @AfterAll
    static void stopEverything() {
        if (ordering != null) {
            ordering.close();
        }
        if (inventory != null) {
            inventory.close();
        }
        if (broker != null) {
            broker.destroy();
        }
    }

    @Test
    void stockReservedConfirmsTheOrderAcrossTheBroker() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

        assertEquals("CONFIRMED", awaitStatus(orderId));
    }

    @Test
    void reservationFailureCompensatesByCancellingTheOrder() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD"))));

        assertEquals("CANCELLED", awaitStatus(orderId));
    }

    private static ConfigurableApplicationContext boot(Class<?> app, String configName, String brokers) {
        return new SpringApplicationBuilder(app)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.config.name=" + configName,
                        "spring.kafka.bootstrap-servers=" + brokers,
                        "aipersimmon.ddd.outbox.poll-delay-ms=200")
                .run();
    }

    private static String awaitStatus(String orderId) {
        long deadline = System.currentTimeMillis() + 30_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = queryBus.ask(new FindOrder(orderId)).map(s -> s.status()).orElse(null);
            if ("CONFIRMED".equals(status) || "CANCELLED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }
}

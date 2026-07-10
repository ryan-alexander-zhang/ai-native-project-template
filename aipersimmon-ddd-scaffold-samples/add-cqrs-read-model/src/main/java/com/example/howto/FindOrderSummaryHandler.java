package com.example.howto;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Answers {@link FindOrderSummary} by reading the read-model table directly. */
@Component
public class FindOrderSummaryHandler implements QueryHandler<FindOrderSummary, OrderSummary> {

    private final JdbcTemplate jdbc;

    public FindOrderSummaryHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OrderSummary handle(FindOrderSummary query) {
        return jdbc.queryForObject(
                "SELECT order_id, sku, status FROM order_summary WHERE order_id = ?",
                (rs, rowNum) -> new OrderSummary(
                        rs.getString("order_id"), rs.getString("sku"), rs.getString("status")),
                query.orderId());
    }
}

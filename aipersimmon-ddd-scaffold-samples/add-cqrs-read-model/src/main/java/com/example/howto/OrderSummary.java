package com.example.howto;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** Read model answering order queries, built by the projection and never by the aggregate. */
@ReadModel
public record OrderSummary(String orderId, String sku, String status) {
}

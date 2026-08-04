package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** The list page's query. Goes through the QueryBus, like every read in the library's vocabulary. */
public record BrowseOrderList(@NotBlank String customerId, @Positive @Max(100) int limit)
    implements Query<List<OrderListItem>> {}

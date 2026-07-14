package com.example.ordering.adapter.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge-tier validation slice: drives {@link OrderController} through MockMvc with the
 * command/query buses mocked, so it verifies only the web concern — that {@code @Valid}
 * on the request DTO rejects a malformed body with 400 before any command is
 * dispatched. The command bus's own validation is a separate, non-web concern tested
 * at the application layer.
 */
@WebMvcTest(OrderController.class)
class OrderControllerValidationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CommandBus commandBus;

    @MockitoBean
    QueryBus queryBus;

    @Test
    void blankCustomerAndEmptyLinesAreRejectedWithoutDispatching() throws Exception {
        String body = """
                {"customerId": "", "lines": []}
                """;

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(commandBus, never()).send(any());
    }

    @Test
    void wellFormedRequestIsDispatchedAndReturns201() throws Exception {
        doReturn("ord-1").when(commandBus).send(any());
        String body = """
                {"customerId": "cust-1",
                 "lines": [{"sku": "SKU-1", "quantity": 2,
                            "unitAmountMinor": 1500, "currency": "USD"}]}
                """;

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/ord-1"));

        verify(commandBus).send(any());
    }
}

package com.example;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end contract for the exception model over HTTP: a business-rule violation
 * renders an RFC 9457 problem with the corrected status (422) and the stable domain
 * code, and a missing aggregate renders 404 — both driven through the real web stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionContractTest {

    private final MockMvc mvc;

    ExceptionContractTest(@Autowired MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void creditExceededRendersProblemWith422AndCode() throws Exception {
        // CUST-1 is seeded with 100_000 credit; a 200_000 order exceeds it.
        String body = """
                {"customerId":"CUST-1",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":200000,"currency":"USD"}]}
                """;

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("/problems/credit-exceeded"))
                .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"));
    }

    @Test
    void duplicateSkuViolatesAggregateRuleWith422AndCode() throws Exception {
        // Two lines with the same SKU breaks the Order.checkInvariant(OrderHasDistinctSkus) invariant.
        String body = """
                {"customerId":"CUST-1",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"},
                          {"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}
                """;

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("ordering.duplicate-sku"))
                .andExpect(jsonPath("$.type").value("/problems/duplicate-sku"));
    }

    @Test
    void unknownCustomerRendersProblemWith404AndCode() throws Exception {
        String body = """
                {"customerId":"NOPE",
                 "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
                """;

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ordering.customer-not-found"));
    }

    @Test
    void unknownOrderOnConfirmRenders404() throws Exception {
        mvc.perform(post("/orders/NON-EXISTENT/confirm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ordering.order-not-found"));
    }

    @Test
    void missingOrderReadRenders404() throws Exception {
        mvc.perform(get("/orders/NON-EXISTENT"))
                .andExpect(status().isNotFound());
    }
}

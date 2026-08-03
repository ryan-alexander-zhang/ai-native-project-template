package com.example.samples.s18.ordering.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s18.ordering.application.OrderView;
import com.example.samples.s18.ordering.application.PlaceOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Layer 3 — the HTTP edge as a slice.
 *
 * <p>{@code @WebMvcTest} starts the web layer and nothing else, and the two buses are stubbed. That is
 * enough for what this controller is responsible for: does the body become the right command, and does
 * an invalid body become the right problem document. No database, no container, no handlers.
 *
 * <p>What it cannot answer is whether that command has a handler at all — the bus is a stub, and a
 * missing or duplicate handler is a startup failure only a real context surfaces. That is what an
 * end-to-end test is for, and why one or two of those per service is usually enough.
 */
@WebMvcTest
// A slice starts only the web-related auto-configurations, and the library's exception advice is not
// one of them: without this import the 400 below has no problem body at all, and the contract S1
// defines cannot be asserted here. Finding that out from a passing status assertion is easy; finding
// it out in production is not.
@ImportAutoConfiguration(com.aipersimmon.ddd.web.spring.AipersimmonDddWebAutoConfiguration.class)
class OrderControllerSliceTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private CommandBus commandBus;

  @MockitoBean private QueryBus queryBus;

  @Test
  void theBodyBecomesACommandAndTheViewComesBack() throws Exception {
    given(commandBus.send(any(PlaceOrder.class))).willReturn("order-1");
    given(queryBus.ask(any())).willReturn(new OrderView("order-1", "customer-1", 2500, "PLACED"));

    mvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"customer-1\",\"amountCents\":2500}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("order-1"));

    ArgumentCaptor<PlaceOrder> sent = ArgumentCaptor.forClass(PlaceOrder.class);
    verify(commandBus).send(sent.capture());
    assertThat(sent.getValue()).isEqualTo(new PlaceOrder("customer-1", 2500));
  }

  @Test
  void aninvalidBodyNeverReachesTheBus() throws Exception {
    mvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"\",\"amountCents\":0}"))
        .andExpect(status().isBadRequest())
        // Bean Validation failures render as about:blank with field errors rather than the
        // /problems/validation-failed family — see S1's error contract.
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.errors").isNotEmpty());

    verifyNoInteractions(commandBus);
  }
}

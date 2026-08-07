package com.example.samples.s23.billing.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s23.billing.application.FindInvoice;
import com.example.samples.s23.billing.application.InvoiceView;
import com.example.samples.s23.billing.application.RaiseInvoice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Billing's entry. */
@RestController
@RequestMapping("/invoices")
class InvoiceController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  InvoiceController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> raise(@Valid @RequestBody RaiseInvoiceRequest request) {
    String id = commandBus.send(new RaiseInvoice(request.orderId(), request.amountMinor()));
    return ResponseEntity.created(URI.create("/invoices/" + id)).body(Map.of("id", id));
  }

  @GetMapping("/{id}")
  ResponseEntity<InvoiceView> invoice(@PathVariable String id) {
    return queryBus
        .ask(new FindInvoice(id))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record RaiseInvoiceRequest(@NotBlank String orderId, @Positive long amountMinor) {}
}

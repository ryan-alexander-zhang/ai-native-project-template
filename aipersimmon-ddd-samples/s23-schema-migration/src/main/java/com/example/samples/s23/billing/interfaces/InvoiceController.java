package com.example.samples.s23.billing.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s23.billing.application.RaiseInvoice;
import com.example.samples.s23.billing.domain.InvoiceId;
import com.example.samples.s23.billing.domain.Invoices;
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
  private final Invoices invoices;

  InvoiceController(CommandBus commandBus, Invoices invoices) {
    this.commandBus = commandBus;
    this.invoices = invoices;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> raise(@Valid @RequestBody RaiseInvoiceRequest request) {
    String id = commandBus.send(new RaiseInvoice(request.orderId(), request.amountMinor()));
    return ResponseEntity.created(URI.create("/invoices/" + id)).body(Map.of("id", id));
  }

  @GetMapping("/{id}")
  ResponseEntity<Map<String, Object>> invoice(@PathVariable String id) {
    return invoices
        .find(new InvoiceId(id))
        .map(
            invoice ->
                Map.<String, Object>of(
                    "id", invoice.id().value(),
                    "orderId", invoice.orderId(),
                    "amountMinor", invoice.amountMinor()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  record RaiseInvoiceRequest(@NotBlank String orderId, @Positive long amountMinor) {}
}

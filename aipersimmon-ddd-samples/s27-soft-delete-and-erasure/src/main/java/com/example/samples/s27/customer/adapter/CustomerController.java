package com.example.samples.s27.customer.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s27.customer.application.ChangeEmail;
import com.example.samples.s27.customer.application.CloseCustomer;
import com.example.samples.s27.customer.application.EraseCustomer;
import com.example.samples.s27.customer.application.RegisterCustomer;
import com.example.samples.s27.customer.application.ReopenCustomer;
import com.example.samples.s27.customer.application.RestoreCustomer;
import com.example.samples.s27.customer.application.SuppressCustomer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seven endpoints, and the URLs are the argument.
 *
 * <p>Three different things could all have been spelled {@code DELETE /customers/{id}}, and the sample refuses to
 * spell any of them that way:
 *
 * <ul>
 *   <li>{@code POST /customers/{id}/closure} — a domain state change, with a reason in the body, undone by
 *       {@code DELETE} of the same sub-resource. Closing is something that happens to a <em>customer</em>.
 *   <li>{@code PUT /customers/{id}/suppression} — an infrastructure switch over a <em>row</em>, named after what
 *       it actually is, so nobody calls it thinking the data is gone.
 *   <li>{@code POST /customers/{id}/erasure} — a compliance operation that takes a ticket reference and is
 *       irreversible. There is no {@code DELETE} counterpart because there is no undo.
 * </ul>
 *
 * <p>A single {@code DELETE} would have made all three indistinguishable to every caller and every log — and the
 * one that is irreversible would have been the easiest to invoke by accident.
 */
@RestController
class CustomerController {

  private final CommandBus commands;

  CustomerController(CommandBus commands) {
    this.commands = commands;
  }

  record RegisterRequest(
      @NotBlank String customerId,
      @NotBlank @Email String email,
      @NotBlank String displayName,
      String phone) {}

  record EmailRequest(@NotBlank @Email String email) {}

  record ClosureRequest(String reason) {}

  record ErasureRequest(@NotBlank String ticket) {}

  @PostMapping("/customers")
  ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
    commands.send(
        new RegisterCustomer(
            request.customerId(), request.email(), request.displayName(), request.phone()));
    return ResponseEntity.created(URI.create("/customers/" + request.customerId())).build();
  }

  @PutMapping("/customers/{id}/email")
  ResponseEntity<Void> changeEmail(
      @PathVariable String id, @Valid @RequestBody EmailRequest request) {
    commands.send(new ChangeEmail(id, request.email()));
    return ResponseEntity.noContent().build();
  }

  /** The domain deletion. */
  @PostMapping("/customers/{id}/closure")
  ResponseEntity<Void> close(@PathVariable String id, @RequestBody ClosureRequest request) {
    commands.send(new CloseCustomer(id, request.reason()));
    return ResponseEntity.noContent().build();
  }

  /** Undone by deleting the sub-resource, which is what makes it visibly reversible. */
  @DeleteMapping("/customers/{id}/closure")
  ResponseEntity<Void> reopen(@PathVariable String id) {
    commands.send(new ReopenCustomer(id));
    return ResponseEntity.noContent().build();
  }

  /** The infrastructure switch, named after the row rather than the customer. */
  @PutMapping("/customers/{id}/suppression")
  ResponseEntity<Void> suppress(@PathVariable String id) {
    return commands.send(new SuppressCustomer(id))
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  @DeleteMapping("/customers/{id}/suppression")
  ResponseEntity<Void> restore(@PathVariable String id) {
    return commands.send(new RestoreCustomer(id))
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /**
   * The compliance erasure. No {@code DELETE} counterpart, because there is no undo.
   *
   * <p>202 rather than 204: the erasure may refuse while announcements are queued, so the caller's request is
   * accepted as an obligation to be discharged rather than as an instruction already carried out. A retry is
   * expected and is a no-op once it has run.
   */
  @PostMapping("/customers/{id}/erasure")
  ResponseEntity<Void> erase(@PathVariable String id, @Valid @RequestBody ErasureRequest request) {
    commands.send(new EraseCustomer(id, request.ticket()));
    return ResponseEntity.accepted().build();
  }
}

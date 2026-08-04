package com.example.samples.s28.reconciliation.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s28.reconciliation.application.CancelExport;
import com.example.samples.s28.reconciliation.application.ExportDownload;
import com.example.samples.s28.reconciliation.application.ExportDownloads;
import com.example.samples.s28.reconciliation.application.ExportJobQuery;
import com.example.samples.s28.reconciliation.application.ExportJobView;
import com.example.samples.s28.reconciliation.application.InlineExport;
import com.example.samples.s28.reconciliation.application.RetryExport;
import com.example.samples.s28.reconciliation.application.SubmitExport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The asynchronous contract, and the synchronous one next to it so they can be compared.
 *
 * <h2>The contract, in five endpoints</h2>
 *
 * <pre>
 *   PUT    /exports/{id}          → 202 + Location, always. The request is accepted, not carried out.
 *   GET    /exports/{id}          → the job: status, progress, attempt, failure, and a content link when there
 *                                   is content. The only endpoint a client polls.
 *   GET    /exports/{id}/content  → the bytes, once there are bytes. 409 while there are not — the job exists,
 *                                   so 404 would be a lie.
 *   DELETE /exports/{id}          → ask it to stop. 202, because stopping is not instantaneous either.
 *   POST   /exports/{id}/retries  → try again, after a failure.
 * </pre>
 *
 * <p><strong>{@code PUT} with a client-supplied id, not {@code POST}.</strong> That single choice is what makes the
 * submission idempotent without an idempotency-key store: the second identical request names the job that already
 * exists. S2 needed the store because the resource it created had a server-assigned id; here the id is in the URL,
 * so there is nothing to remember — and nothing whose retention window could expire before the job does.
 *
 * <p><strong>202 both times, and {@code Location} both times.</strong> A client that retried after a timeout must
 * not be able to tell its retry from its first attempt, or it will branch on the difference. Whether this call
 * created the job is in the body, for a human reading a log.
 *
 * <p><strong>No {@code Retry-After} on the 202.</strong> It would be a guess made at the worst possible moment —
 * before any work has happened. The honest pacing hint is the progress reading the client is about to poll: a job at
 * 4% after ten seconds says more about when to come back than any header written at submission time.
 *
 * <p><strong>{@code DELETE} means "ask it to stop", not "remove the record".</strong> Which is worth one sentence of
 * defence: the record of a cancelled export is the answer to "why is there no June file", so deleting it would
 * destroy the only explanation. The job resource outlives the work either way.
 */
@RestController
class ExportController {

  private final CommandBus commands;
  private final QueryBus queries;
  private final InlineExport inline;
  private final ExportDownloads downloads;

  ExportController(
      CommandBus commands, QueryBus queries, InlineExport inline, ExportDownloads downloads) {
    this.commands = commands;
    this.queries = queries;
    this.inline = inline;
    this.downloads = downloads;
  }

  record SubmitRequest(@NotBlank String period) {}

  record SubmitResponse(String exportId, String status, boolean created) {}

  @PutMapping("/exports/{id}")
  ResponseEntity<SubmitResponse> submit(
      @PathVariable String id, @Valid @RequestBody SubmitRequest request) {
    boolean created = commands.send(new SubmitExport(id, request.period()));
    return ResponseEntity.accepted()
        .location(URI.create("/exports/" + id))
        .body(new SubmitResponse(id, "QUEUED", created));
  }

  @GetMapping("/exports/{id}")
  ExportJobView poll(@PathVariable String id) {
    return queries.ask(new ExportJobQuery(id));
  }

  /**
   * The bytes.
   *
   * <p>Note what is not happening: no query over the source table, no cursor, no transaction. The download's
   * duration is entirely the client's business, which is the payoff for having written a file — compare
   * {@link #exportInline}, where a slow client holds a database connection for as long as it likes.
   */
  @GetMapping("/exports/{id}/content")
  ResponseEntity<StreamingResponseBody> content(@PathVariable String id) {
    ExportDownload download = downloads.open(id);
    StreamingResponseBody body =
        out -> {
          try (InputStream in = download.stream()) {
            in.transferTo(out);
          }
        };
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + download.filename() + "\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .contentLength(download.bytes())
        .body(body);
  }

  /** Ask it to stop. 202: the worker acknowledges at its next chunk boundary. */
  @DeleteMapping("/exports/{id}")
  ResponseEntity<Void> cancel(@PathVariable String id) {
    commands.send(new CancelExport(id));
    return ResponseEntity.accepted().location(URI.create("/exports/" + id)).build();
  }

  /** A sub-resource, because a retry is a new attempt at the job rather than a change to it. */
  @PostMapping("/exports/{id}/retries")
  ResponseEntity<Void> retry(@PathVariable String id) {
    commands.send(new RetryExport(id));
    return ResponseEntity.accepted().location(URI.create("/exports/" + id)).build();
  }

  /**
   * The synchronous export, for comparison and for small periods.
   *
   * <p>It streams rather than buffering, so it is the <em>good</em> version of the shape — and it still holds a
   * pooled database connection for as long as the response takes, which is where the real limit is. Kept on a
   * separate path so nobody reaches it by accident, and returned with no content length, because the server does
   * not know how long it will be until it has finished writing it.
   */
  @GetMapping(value = "/exports/inline", produces = "text/csv")
  ResponseEntity<StreamingResponseBody> exportInline(@RequestParam String period) {
    StreamingResponseBody body =
        out -> {
          try (PrintWriter writer = new PrintWriter(out, false, StandardCharsets.UTF_8)) {
            inline.writeTo(period, writer);
          }
        };
    return ResponseEntity.ok().body(body);
  }
}

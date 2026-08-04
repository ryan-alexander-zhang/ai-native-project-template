package com.example.samples.s28.reconciliation.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s28.reconciliation.application.AbandonImport;
import com.example.samples.s28.reconciliation.application.AcceptChunk;
import com.example.samples.s28.reconciliation.application.CompleteImport;
import com.example.samples.s28.reconciliation.application.ImportBatchQuery;
import com.example.samples.s28.reconciliation.application.ImportBatchView;
import com.example.samples.s28.reconciliation.application.OpenImport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A resumable upload, in four endpoints and one idea: <strong>every one of them can be repeated.</strong>
 *
 * <pre>
 *   PUT    /imports/{id}                 → open it (or recognise that it is open). Declares the chunk count.
 *   PUT    /imports/{id}/chunks/{n}      → send chunk n. Twice is free; out of order is fine.
 *   GET    /imports/{id}                 → what is still missing. The resume endpoint.
 *   POST   /imports/{id}/completion      → close it. Refused, naming the gaps, while any chunk is absent.
 *   DELETE /imports/{id}                 → give up, with a reason.
 * </pre>
 *
 * <p>Repeatability is not a nicety here, it is the protocol. A resume is by definition a client that does not know
 * how much of what it sent arrived — that is what the failure destroyed — so if the second attempt at anything is an
 * error, resuming is impossible and the only recovery is to start again. Every verb above is {@code PUT} or an
 * idempotent {@code POST} for that reason, and the ids are the client's.
 *
 * <p>The chunk number is in the URL rather than inferred from arrival order, which is what allows the chunks to be
 * sent in parallel — and makes "which ones landed" a question with an exact answer rather than a high-water mark.
 *
 * <p>The payload is a JSON string of lines, which is a simplification the sample is making on purpose: multipart
 * bodies and content ranges are HTTP detail, and the questions this scenario is about — what is idempotent, what is
 * checked, what "resumable" requires the server to remember — are the same either way.
 */
@RestController
class ImportController {

  private final CommandBus commands;
  private final QueryBus queries;

  ImportController(CommandBus commands, QueryBus queries) {
    this.commands = commands;
    this.queries = queries;
  }

  record OpenRequest(@Min(1) int chunks) {}

  record OpenResponse(String batchId, int chunks, boolean created) {}

  record ChunkRequest(@NotBlank String checksum, @NotNull String payload) {}

  record ChunkResponse(int chunkNumber, boolean stored) {}

  record AbandonRequest(String reason) {}

  @PutMapping("/imports/{id}")
  ResponseEntity<OpenResponse> open(
      @PathVariable String id, @Valid @RequestBody OpenRequest request) {
    boolean created = commands.send(new OpenImport(id, request.chunks()));
    return ResponseEntity.accepted()
        .location(URI.create("/imports/" + id))
        .body(new OpenResponse(id, request.chunks(), created));
  }

  /**
   * One chunk.
   *
   * <p>{@code stored} is false when the chunk was already on record, and the status is 200 either way. Answering
   * 409 for a duplicate would be technically defensible and practically hostile: a client resending after a timeout
   * would have to treat the conflict as success, which is a rule nobody remembers to implement.
   */
  @PutMapping("/imports/{id}/chunks/{n}")
  ChunkResponse chunk(
      @PathVariable String id, @PathVariable int n, @Valid @RequestBody ChunkRequest request) {
    boolean stored = commands.send(new AcceptChunk(id, n, request.checksum(), request.payload()));
    return new ChunkResponse(n, stored);
  }

  /** The resume endpoint: what the server still wants. */
  @GetMapping("/imports/{id}")
  ImportBatchView poll(@PathVariable String id) {
    return queries.ask(new ImportBatchQuery(id));
  }

  /** Close it. A sub-resource rather than a {@code PUT} of the batch, so the intent is in the URL. */
  @PostMapping("/imports/{id}/completion")
  ResponseEntity<ImportBatchView> complete(@PathVariable String id) {
    commands.send(new CompleteImport(id));
    return ResponseEntity.ok(queries.ask(new ImportBatchQuery(id)));
  }

  @DeleteMapping("/imports/{id}")
  ResponseEntity<Void> abandon(@PathVariable String id, @RequestBody AbandonRequest request) {
    commands.send(new AbandonImport(id, request.reason()));
    return ResponseEntity.noContent().build();
  }
}

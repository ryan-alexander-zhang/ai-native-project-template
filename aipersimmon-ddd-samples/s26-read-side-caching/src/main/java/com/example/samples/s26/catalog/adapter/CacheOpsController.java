package com.example.samples.s26.catalog.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s26.catalog.application.CacheAudit;
import com.example.samples.s26.catalog.application.CacheKeys;
import com.example.samples.s26.catalog.application.CacheTelemetry;
import com.example.samples.s26.catalog.application.QueryCache;
import com.example.samples.s26.catalog.application.RebuildSalesBoard;
import com.example.samples.s26.catalog.application.SalesWindow;
import com.example.samples.s26.catalog.domain.Sku;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four things an operator needs from a cache: what it is doing, whether it is lying, how to drop it, and
 * how to repair the projection beside it.
 *
 * <p>The interesting one is {@code DELETE} versus {@code POST /rebuild}, side by side, because it is the whole
 * cache-versus-projection argument reduced to two endpoints. Flushing the cache is instant, always safe, and
 * restores nothing — the next reads are slow and correct. Rebuilding the projection takes as long as the
 * source table is big, has to be atomic, and <em>restores a correct table</em>. When somebody asks which of
 * the two to reach for during an incident, the answer is in the difference between those sentences.
 */
@RestController
@RequestMapping("/ops")
class CacheOpsController {

  private final QueryCache cache;
  private final CacheTelemetry telemetry;
  private final CacheAudit audit;
  private final CommandBus commands;

  CacheOpsController(
      QueryCache cache, CacheTelemetry telemetry, CacheAudit audit, CommandBus commands) {
    this.cache = cache;
    this.telemetry = telemetry;
    this.audit = audit;
    this.commands = commands;
  }

  /**
   * The counters.
   *
   * <p>{@code databaseReads} against {@code hits} is the only pair that says whether the cache is worth its
   * existence; {@code divergences} is the one that needs an alert, because it is the only number that goes the
   * wrong way when the cache stops being invalidated. See {@link CacheTelemetry}.
   */
  @GetMapping("/cache")
  Map<String, Object> stats() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("hits", telemetry.getHits());
    body.put("misses", telemetry.getMisses());
    body.put("coalesced", telemetry.getCoalesced());
    body.put("evictions", telemetry.getEvictions());
    body.put("databaseReads", telemetry.getDatabaseReads());
    body.put("divergences", telemetry.getDivergences());
    body.put("writeFailures", telemetry.getWriteFailures());
    return body;
  }

  /**
   * Drop this tenant's entries — not everybody's.
   *
   * <p>A flush scoped to the tenant is possible only because the tenant leads the key. Scoped to nothing, this
   * endpoint would be {@code FLUSHDB} with better manners, and on a Redis shared with other services that is
   * an outage for people who have never heard of this one.
   */
  @DeleteMapping("/cache")
  Map<String, Object> flush() {
    int removed = cache.evictMatching(CacheKeys.allOf(TenantContext.effective()));
    return Map.of("tenant", TenantContext.effective().value(), "removed", removed);
  }

  /** Is the entry for this product still telling the truth? */
  @GetMapping("/cache/audit/{sku}")
  ResponseEntity<Map<String, Object>> auditOne(@PathVariable String sku) {
    return audit
        .check(new Sku(sku))
        .<ResponseEntity<Map<String, Object>>>map(
            divergence ->
                ResponseEntity.ok(
                    Map.of(
                        "sku", divergence.sku(),
                        "diverged", true,
                        "cached", String.valueOf(divergence.cached()),
                        "actual", String.valueOf(divergence.actual()))))
        .orElseGet(() -> ResponseEntity.ok(Map.of("sku", sku, "diverged", false)));
  }

  /** Recompute the projection. The operation the cache has no equivalent of. */
  @PostMapping("/projection/rebuild")
  Map<String, Object> rebuild() {
    Integer rows = commands.send(new RebuildSalesBoard(SalesWindow.RECENT));
    return Map.of("rowsWritten", rows);
  }
}

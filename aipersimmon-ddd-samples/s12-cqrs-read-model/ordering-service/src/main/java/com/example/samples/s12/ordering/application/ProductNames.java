package com.example.samples.s12.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This context's own replica of the product names it displays.
 *
 * <p><strong>Not a cache and not a client.</strong> There is no TTL and no fallback call to the catalogue:
 * the rows are here because the catalogue told this context about them, and they stay here until it says
 * otherwise. That makes them <em>this</em> context's asset — the ordering service can be queried, backed
 * up, rebuilt and reasoned about with the catalogue switched off.
 *
 * <p>Which is the answer to the catalogue's ownership question, and the line it draws: the ordering service
 * may keep a copy of another context's data and serve it; it may not reach into the catalogue's database,
 * and no third context may read this replica. A replica is legitimate exactly when the copy is owned by the
 * holder and fed by published events. The same table read by a service that did not subscribe to those
 * events would be a back door with a schema.
 */
public interface ProductNames {

  Map<String, String> namesOf(List<String> skus);

  Optional<String> nameOf(String sku);

  /** Record what the catalogue said. Idempotent by primary key. */
  void record(String sku, String name, Instant at);
}

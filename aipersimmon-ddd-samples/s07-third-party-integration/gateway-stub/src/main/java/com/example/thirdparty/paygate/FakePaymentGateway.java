package com.example.thirdparty.paygate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A payment gateway we do not own, do not control, and cannot fix.
 *
 * <p>Three surfaces, which is the shape almost every provider has:
 *
 * <ul>
 *   <li>{@code POST /charges} — take the money. Answers {@code 202} and nothing else of substance:
 *       the outcome arrives later, by callback.
 *   <li>{@code POST <callback-url>} — tell the merchant what happened. Signed, at-least-once, in no
 *       particular order.
 *   <li>{@code GET /charges/{merchant_ref}} — the pull channel, and the only thing that can rescue a
 *       payment whose callback never came.
 * </ul>
 *
 * <p>Its vocabulary is not ours: {@code merchant_ref}, {@code txn_ref}, {@code result_code} with
 * numeric values borrowed from card-network response codes. That is not decoration — it is the reason
 * the payment service needs a translation layer, and a stub that spoke our language would quietly
 * remove the problem the anticorruption layer exists to solve.
 *
 * <p>Its concurrency is real ({@link Executors#newFixedThreadPool}), not the JDK default of one
 * thread. S6 learned that the hard way: a single-threaded stub blocks every request behind the
 * slowest one and produces failures that look like bugs in the caller.
 */
public final class FakePaymentGateway implements AutoCloseable {

  /** Accepted, no decision yet. */
  public static final String RESULT_PENDING = "PND";

  /** Approved. The card networks' "00", and no more self-describing here than there. */
  public static final String RESULT_APPROVED = "00";

  /** Declined for insufficient funds. A business outcome, not an error. */
  public static final String RESULT_INSUFFICIENT_FUNDS = "51";

  /** A code added after the consumer was written. Every provider does this. */
  public static final String RESULT_ADDED_LATER = "77";

  private final HttpServer server;
  private final String secret;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ScheduledExecutorService callbacks =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "paygate-callbacks");
            thread.setDaemon(true);
            return thread;
          });

  private final Map<String, Charge> chargesByKey = new ConcurrentHashMap<>();
  private final AtomicReference<GatewayMode> mode = new AtomicReference<>(GatewayMode.NORMAL);
  private final AtomicReference<String> callbackUrl = new AtomicReference<>();
  private final AtomicInteger chargeRequests = new AtomicInteger();
  private final AtomicInteger sequence = new AtomicInteger();
  private final List<Integer> callbackResponses = Collections.synchronizedList(new ArrayList<>());
  private final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());

  private FakePaymentGateway(HttpServer server, String secret) {
    this.server = server;
    this.secret = secret;
  }

  /** Starts on an ephemeral port. The secret is the one the merchant was given out of band. */
  public static FakePaymentGateway start(String secret) {
    return start(0, secret);
  }

  public static FakePaymentGateway start(int port, String secret) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
      FakePaymentGateway gateway = new FakePaymentGateway(server, secret);
      server.createContext("/charges", gateway::handleCharges);
      server.createContext("/_control", gateway::handleControl);
      server.setExecutor(Executors.newFixedThreadPool(4));
      server.start();
      return gateway;
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the fake gateway", e);
    }
  }

  public String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /** Where callbacks go. A real merchant configures this in a dashboard; here, in one call. */
  public void callbacksTo(String url) {
    callbackUrl.set(url);
  }

  public void mode(GatewayMode next) {
    mode.set(next);
  }

  /** Forgets every charge and counter. The stub outlives a single test; its state must not. */
  public void reset() {
    chargesByKey.clear();
    chargeRequests.set(0);
    sequence.set(0);
    callbackResponses.clear();
    idempotencyKeys.clear();
    mode.set(GatewayMode.NORMAL);
  }

  /**
   * Every {@code Idempotency-Key} this gateway has been sent, in order, including on requests it
   * refused. What makes "the retry reused the key" an assertion rather than a hope.
   */
  public List<String> idempotencyKeys() {
    synchronized (idempotencyKeys) {
      return List.copyOf(idempotencyKeys);
    }
  }

  /** Charge requests received, including ones this gateway refused. */
  public int chargeRequests() {
    return chargeRequests.get();
  }

  /** Distinct charges created — the number that must stay at one however often we are asked. */
  public int chargesCreated() {
    return chargesByKey.size();
  }

  /** The HTTP statuses the merchant answered our callbacks with, in order. */
  public List<Integer> callbackResponses() {
    synchronized (callbackResponses) {
      return List.copyOf(callbackResponses);
    }
  }

  /** The gateway's own view of a charge, for tests that need to know what the truth was. */
  public Optional<String> resultCodeOf(String merchantRef) {
    return chargesByKey.values().stream()
        .filter(charge -> charge.merchantRef.equals(merchantRef))
        .findFirst()
        .map(charge -> charge.resultCode);
  }

  @Override
  public void close() {
    callbacks.shutdownNow();
    server.stop(0);
  }

  // ---------------------------------------------------------------- request handling

  private void handleCharges(HttpExchange exchange) throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) {
      handleStatusQuery(exchange);
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, Map.of("error", "method_not_allowed"));
      return;
    }
    handleChargeRequest(exchange);
  }

  private void handleChargeRequest(HttpExchange exchange) throws IOException {
    chargeRequests.incrementAndGet();
    String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
    byte[] body = exchange.getRequestBody().readAllBytes();

    if (idempotencyKey != null) {
      idempotencyKeys.add(idempotencyKey);
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      // A gateway that accepts a charge with no key is a gateway that will charge twice. Ours does
      // not, so the payment service cannot pass this test by accident.
      respond(exchange, 400, Map.of("error", "missing_idempotency_key"));
      return;
    }
    if (mode.get() == GatewayMode.FAIL_FIRST_CHARGE && chargeRequests.get() == 1) {
      respond(exchange, 503, Map.of("error", "temporarily_unavailable"));
      return;
    }
    if (mode.get() == GatewayMode.REFUSE_CHARGE_REQUEST) {
      respond(exchange, 400, Map.of("error", "invalid_request", "detail", "unsupported currency"));
      return;
    }

    JsonNode request = json.readTree(body);
    String merchantRef = request.path("merchant_ref").asText();

    Charge existing = chargesByKey.get(idempotencyKey);
    if (existing != null) {
      // The whole point of the key: the second request is answered from the first result, and no
      // second charge exists. Note it answers 202 again rather than 409 — a retry is not an error.
      respond(exchange, 202, Map.of("txn_ref", existing.txnRef, "duplicate_of", existing.txnRef));
      return;
    }

    String txnRef = "gw_" + sequence.incrementAndGet();
    if (mode.get() == GatewayMode.FORGET_CHARGE) {
      // Accepted, and then lost. The response is indistinguishable from a charge that was stored.
      respond(exchange, 202, Map.of("txn_ref", txnRef));
      return;
    }

    Charge charge = new Charge(txnRef, merchantRef, request.path("amount_minor").asLong());
    chargesByKey.put(idempotencyKey, charge);

    if (mode.get() == GatewayMode.LOSE_FIRST_RESPONSE && chargeRequests.get() == 1) {
      // Charged, and then the answer is lost. The caller sees a 503 and cannot tell this apart from a
      // request that never landed — which is the whole reason the key exists.
      charge.resultCode = RESULT_APPROVED;
      respond(exchange, 503, Map.of("error", "gateway_timeout_after_capture"));
      return;
    }

    respond(exchange, 202, Map.of("txn_ref", txnRef));
    scheduleCallbacks(charge);
  }

  private void handleStatusQuery(HttpExchange exchange) throws IOException {
    if (mode.get() == GatewayMode.STATUS_QUERY_FAILS) {
      respond(exchange, 503, Map.of("error", "temporarily_unavailable"));
      return;
    }
    String path = exchange.getRequestURI().getPath();
    String merchantRef = path.substring(path.lastIndexOf('/') + 1);
    Optional<Charge> charge =
        chargesByKey.values().stream().filter(c -> c.merchantRef.equals(merchantRef)).findFirst();
    if (charge.isEmpty()) {
      // Not 200-with-nothing: "I have no record of this" is a distinct answer and the merchant has
      // to be able to tell it from "still deciding".
      respond(exchange, 404, Map.of("error", "unknown_charge"));
      return;
    }
    Charge found = charge.get();
    respond(
        exchange,
        200,
        Map.of(
            "txn_ref", found.txnRef,
            "merchant_ref", found.merchantRef,
            "amount_minor", found.amountMinor,
            "result_code", found.resultCode,
            "result_desc", describe(found.resultCode)));
  }

  /** For driving a standalone run by hand: {@code {"mode":"SILENT"}}. */
  private void handleControl(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, Map.of("error", "method_not_allowed"));
      return;
    }
    JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
    if (request.hasNonNull("mode")) {
      mode.set(GatewayMode.valueOf(request.get("mode").asText()));
    }
    if (request.hasNonNull("callback_url")) {
      callbackUrl.set(request.get("callback_url").asText());
    }
    respond(exchange, 200, Map.of("mode", mode.get().name()));
  }

  // ---------------------------------------------------------------- callbacks

  private void scheduleCallbacks(Charge charge) {
    switch (mode.get()) {
      case SILENT -> charge.resultCode = RESULT_APPROVED;
      case SILENT_PENDING, FORGET_CHARGE -> {
        /* stays pending, says nothing */
      }
      case NORMAL -> {
        notifyAfter(60, charge, RESULT_PENDING);
        notifyAfter(120, charge, RESULT_APPROVED);
      }
      case REVERSED_CALLBACKS -> {
        notifyAfter(60, charge, RESULT_APPROVED);
        notifyAfter(120, charge, RESULT_PENDING);
      }
      case DUPLICATE_CALLBACK -> {
        notifyAfter(60, charge, RESULT_APPROVED);
        notifyAfter(120, charge, RESULT_APPROVED);
      }
      case DECLINE -> {
        notifyAfter(60, charge, RESULT_PENDING);
        notifyAfter(120, charge, RESULT_INSUFFICIENT_FUNDS);
      }
      case UNKNOWN_RESULT_CODE -> {
        notifyAfter(60, charge, RESULT_PENDING);
        notifyAfter(120, charge, RESULT_ADDED_LATER);
      }
      case CONTRADICTORY_CALLBACKS -> {
        notifyAfter(60, charge, RESULT_APPROVED);
        notifyAfter(180, charge, RESULT_INSUFFICIENT_FUNDS);
      }
      case FAIL_FIRST_CHARGE, LOSE_FIRST_RESPONSE -> {
        notifyAfter(60, charge, RESULT_PENDING);
        notifyAfter(120, charge, RESULT_APPROVED);
      }
      case REFUSE_CHARGE_REQUEST -> {
        /* unreachable: the request was refused before a charge existed */
      }
      case STATUS_QUERY_FAILS -> {
        // Charged, and silent about it: this mode exists to test the pull channel's failure, so the
        // payment must be resolvable only by asking — and asking is what fails.
        charge.resultCode = RESULT_APPROVED;
      }
    }
  }

  private void notifyAfter(long delayMillis, Charge charge, String resultCode) {
    callbacks.schedule(() -> notifyNow(charge, resultCode), delayMillis, TimeUnit.MILLISECONDS);
  }

  private void notifyNow(Charge charge, String resultCode) {
    charge.resultCode = resultCode;
    String url = callbackUrl.get();
    if (url == null) {
      return;
    }
    // Every field the merchant will see. `event_id` is fresh on every send, which is exactly why a
    // duplicate notification is not a replay: the bytes differ, so the signature differs too.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", "evt_" + sequence.incrementAndGet());
    payload.put("txn_ref", charge.txnRef);
    payload.put("merchant_ref", charge.merchantRef);
    payload.put("result_code", resultCode);
    payload.put("result_desc", describe(resultCode));
    payload.put("notified_at", Instant.now().toString());

    try {
      String body = json.writeValueAsString(payload);
      long timestamp = Instant.now().getEpochSecond();
      String nonce = "nonce-" + sequence.incrementAndGet();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/json")
                  .header("X-Gateway-Signature", CallbackSigner.sign(timestamp, nonce, body, secret))
                  .header("X-Gateway-Timestamp", Long.toString(timestamp))
                  .header("X-Gateway-Nonce", nonce)
                  .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      callbackResponses.add(response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception delivery) {
      // A real gateway would queue this for redelivery. This one records the failure and moves on,
      // which keeps the tests honest: nothing here retries a callback into eventual success.
      callbackResponses.add(-1);
    }
  }

  private static String describe(String resultCode) {
    return switch (resultCode) {
      case RESULT_PENDING -> "accepted, awaiting authorisation";
      case RESULT_APPROVED -> "approved";
      case RESULT_INSUFFICIENT_FUNDS -> "declined: insufficient funds";
      case RESULT_ADDED_LATER -> "soft decline: issuer requests retry later";
      default -> "unspecified";
    };
  }

  private void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** One charge, as the gateway remembers it. */
  private static final class Charge {

    private final String txnRef;
    private final String merchantRef;
    private final long amountMinor;
    private volatile String resultCode = RESULT_PENDING;

    private Charge(String txnRef, String merchantRef, long amountMinor) {
      this.txnRef = txnRef;
      this.merchantRef = merchantRef;
      this.amountMinor = amountMinor;
    }
  }
}

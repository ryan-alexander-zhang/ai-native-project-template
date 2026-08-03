package com.example.samples.s06;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real HTTP server standing in for the risk service, on a real socket.
 *
 * <p>Deliberately not a mocked {@code RestClient}: the interesting claims here are about the transport —
 * that a read timeout fires, that a 500 is retried once, that a body this caller cannot interpret is not
 * treated as approval. A mock that returns objects skips every one of those, and the JDK has had an HTTP
 * server since Java 6, so the honest version costs nothing.
 *
 * <p>It also counts requests, which is how "retried exactly once" becomes an assertion rather than a hope.
 */
final class RiskStubServer {

  /** What the stub should do with the next request. */
  record Behaviour(int status, String body, Duration delay) {

    static Behaviour approved() {
      return new Behaviour(200, "{\"approved\":true}", Duration.ZERO);
    }

    static Behaviour rejected(String reason) {
      return new Behaviour(
          200, "{\"approved\":false,\"reason\":\"" + reason + "\"}", Duration.ZERO);
    }

    static Behaviour serverError() {
      return new Behaviour(500, "{\"title\":\"Internal Server Error\"}", Duration.ZERO);
    }

    static Behaviour badRequestProblem() {
      return new Behaviour(
          400,
          "{\"type\":\"/problems/validation-failed\",\"code\":\"validation.failed\"}",
          Duration.ZERO);
    }

    static Behaviour unintelligible() {
      return new Behaviour(200, "{\"verdict\":\"maybe\"}", Duration.ZERO);
    }

    static Behaviour slow(Duration delay) {
      return new Behaviour(200, "{\"approved\":true}", delay);
    }
  }

  private final HttpServer server;
  private final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.approved());
  private final AtomicInteger requests = new AtomicInteger();

  private RiskStubServer(HttpServer server) {
    this.server = server;
  }

  static RiskStubServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      RiskStubServer stub = new RiskStubServer(server);
      server.createContext(
          "/risk-assessments",
          exchange -> {
            stub.requests.incrementAndGet();
            Behaviour current = stub.behaviour.get();
            if (!current.delay().isZero()) {
              try {
                Thread.sleep(current.delay().toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      // A real pool, not the default single-threaded executor. With the default, a request that is being
      // delayed blocks the next one from entering the handler at all — so "the client retried" was
      // invisible to the counter and the retry looked like it had not happened. The stub's concurrency has
      // to at least match what the client can have in flight.
      server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
      server.start();
      return stub;
    } catch (IOException e) {
      throw new IllegalStateException("could not start the risk stub", e);
    }
  }

  String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  void willRespond(Behaviour next) {
    behaviour.set(next);
    requests.set(0);
  }

  int requestCount() {
    return requests.get();
  }

  void stop() {
    server.stop(0);
  }
}

package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * The README's quickstart is executed, not merely written (issue-00093).
 *
 * <p>It had three independent defects at review time — wrong port, missing tenant header, and a
 * tenant that has no seed data — and fixing any two still left a command that could not work. None
 * of them was a wrong decision: 8090, {@code missing-policy=REJECT} and a seeded sentinel tenant
 * are each correct and each carefully commented where they are configured. The quickstart was
 * simply the one piece of this README not wired into the "capability → example → verifying test"
 * discipline that keeps the rest of it honest, because "here is what to type" had no test shape.
 * This is that shape.
 *
 * <p><strong>Parsed from the README, never copied.</strong> A copy is a second thing to keep in
 * step and would drift alongside the original while still passing. What runs below is the exact
 * text a reader would paste, so if someone edits the command into something that cannot work, this
 * fails.
 *
 * <p>The port is the one part a test cannot exercise — the server here is on a random one — so it
 * is checked separately against {@code application.yml}, which is where the reader's 8090 has to
 * come from.
 *
 * <p>This guard would have caught issue-00096 on the day it was introduced: the header the
 * quickstart carried named a reserved sentinel the tenancy filter rejects, and every request would
 * have come back 400.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class ReadmeQuickstartTest {

  private static final Path README = Path.of("../README.md");

  @Autowired TestRestTemplate http;

  @Test
  void theQuickstartPlacesAnOrderAndReadsItBack() throws IOException {
    Curl post = Curl.parse(README, "-X POST");

    ResponseEntity<String> placed =
        http.exchange(post.path(), HttpMethod.POST, post.entity(), String.class);

    assertEquals(
        201,
        placed.getStatusCode().value(),
        () ->
            "the README quickstart must work as written — a reader pasting it gets: "
                + placed.getBody());
    assertNotNull(placed.getHeaders().getLocation(), "and a Location to read the order back from");

    // The second command in the quickstart, against the id the first one returned.
    Curl get = Curl.parse(README, "localhost:%d/orders/<id>".formatted(configuredPort()));
    ResponseEntity<String> read =
        http.exchange(
            placed.getHeaders().getLocation().getPath(),
            HttpMethod.GET,
            new HttpEntity<>(get.headers()),
            String.class);

    assertEquals(200, read.getStatusCode().value(), () -> "reading it back: " + read.getBody());
  }

  @Test
  void theReadmePortIsThePortTheApplicationBinds() throws IOException {
    int configured = configuredPort();
    String readme = Files.readString(README);

    Matcher ports = Pattern.compile("localhost:(\\d+)/").matcher(readme);
    List<String> wrong = new ArrayList<>();
    while (ports.find()) {
      if (Integer.parseInt(ports.group(1)) != configured) {
        wrong.add(ports.group());
      }
    }
    assertTrue(
        wrong.isEmpty(),
        () ->
            "the README sends the reader to "
                + wrong
                + " but the application binds "
                + configured
                + " (application.yml). Until kafka-ui moved behind a compose profile, 8080 even"
                + " answered — with an HTML page rather than a refused connection.");
  }

  /** The port a reader gets, read from the file they would read it from. */
  private static int configuredPort() throws IOException {
    List<PropertySource<?>> loaded =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    Object port = loaded.get(0).getProperty("server.port");
    assertNotNull(port, "application.yml must state the port the README advertises");
    return Integer.parseInt(port.toString());
  }

  /** One curl command lifted out of the README's fenced bash blocks. */
  private record Curl(String path, HttpHeaders headers, String body) {

    private static final Pattern HEADER = Pattern.compile("-H '([^:]+): *([^']*)'");
    private static final Pattern BODY = Pattern.compile("-d '(.*)'", Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("localhost:\\d+(/\\S*)");

    /** Finds the first curl containing {@code marker}, joining its backslash continuations. */
    static Curl parse(Path readme, String marker) throws IOException {
      String command =
          Files.readString(readme)
              .replace("\\\n", " ")
              .lines()
              .map(String::trim)
              .filter(line -> line.startsWith("curl ") && line.contains(marker))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "no curl containing '" + marker + "' in the README quickstart"));

      HttpHeaders headers = new HttpHeaders();
      Matcher header = HEADER.matcher(command);
      while (header.find()) {
        headers.set(header.group(1).trim(), header.group(2).trim());
      }

      Matcher url = URL.matcher(command);
      if (!url.find()) {
        throw new AssertionError("no localhost:<port>/<path> in: " + command);
      }
      Matcher body = BODY.matcher(command);
      return new Curl(url.group(1), headers, body.find() ? body.group(1) : null);
    }

    HttpEntity<String> entity() {
      return new HttpEntity<>(body, headers);
    }
  }
}

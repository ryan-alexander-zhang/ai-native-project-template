package com.aipersimmon.ddd.web.spring;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the web layer, under {@code aipersimmon.ddd.web}. The zero-risk concerns
 * (problem details, request id) default on; the stateful, opt-in concerns (idempotency, replay,
 * rate limiting) default off, and use the in-memory SPI implementations unless a store backend
 * module is on the classpath.
 */
@ConfigurationProperties("aipersimmon.ddd.web")
public class AipersimmonDddWebProperties {

  private final ProblemDetails problemDetails = new ProblemDetails();
  private final RequestId requestId = new RequestId();
  private final Idempotency idempotency = new Idempotency();
  private final Replay replay = new Replay();
  private final RateLimit rateLimit = new RateLimit();

  public ProblemDetails getProblemDetails() {
    return problemDetails;
  }

  public RequestId getRequestId() {
    return requestId;
  }

  public Idempotency getIdempotency() {
    return idempotency;
  }

  public Replay getReplay() {
    return replay;
  }

  public RateLimit getRateLimit() {
    return rateLimit;
  }

  /** Exception-to-ProblemDetail mapping. */
  public static class ProblemDetails {

    /** Whether the exception advice is registered. */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Request-id correlation (a per-request id at the edge, not the distributed-trace id). */
  public static class RequestId {

    /** Whether the request-id filter is registered. */
    private boolean enabled = true;

    /** Request/response header carrying the request id. */
    private String header = "X-Request-Id";

    /** Generate a request id when the request does not carry one. */
    private boolean generateIfAbsent = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHeader() {
      return header;
    }

    public void setHeader(String header) {
      this.header = header;
    }

    public boolean isGenerateIfAbsent() {
      return generateIfAbsent;
    }

    public void setGenerateIfAbsent(boolean generateIfAbsent) {
      this.generateIfAbsent = generateIfAbsent;
    }
  }

  /** Idempotency-key handling (reliability: safe retries). Off by default. */
  public static class Idempotency {

    /** Whether the idempotency filter is registered. */
    private boolean enabled = false;

    /** Request header carrying the idempotency key. */
    private String header = "Idempotency-Key";

    /** How long a stored first response is retained. */
    private Duration ttl = Duration.ofHours(24);

    /** Reject a write with 400 when the key is missing (otherwise pass through). */
    private boolean requireKey = false;

    /** HTTP methods the filter applies to. */
    private List<String> methods = List.of("POST", "PUT", "PATCH", "DELETE");

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHeader() {
      return header;
    }

    public void setHeader(String header) {
      this.header = header;
    }

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }

    public boolean isRequireKey() {
      return requireKey;
    }

    public void setRequireKey(boolean requireKey) {
      this.requireKey = requireKey;
    }

    public List<String> getMethods() {
      return methods;
    }

    public void setMethods(List<String> methods) {
      this.methods = methods;
    }
  }

  /** Replay protection (security: reject a captured, signed request). Off by default. */
  public static class Replay {

    /** Whether the replay-protection filter is registered. */
    private boolean enabled = false;

    /** Accepted clock skew between the request timestamp and now. */
    private Duration tolerance = Duration.ofMinutes(5);

    /** Header carrying the request signature. */
    private String signatureHeader = "X-Signature";

    /** Header carrying the request timestamp (epoch seconds). */
    private String timestampHeader = "X-Timestamp";

    private final Nonce nonce = new Nonce();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getTolerance() {
      return tolerance;
    }

    public void setTolerance(Duration tolerance) {
      this.tolerance = tolerance;
    }

    public String getSignatureHeader() {
      return signatureHeader;
    }

    public void setSignatureHeader(String signatureHeader) {
      this.signatureHeader = signatureHeader;
    }

    public String getTimestampHeader() {
      return timestampHeader;
    }

    public void setTimestampHeader(String timestampHeader) {
      this.timestampHeader = timestampHeader;
    }

    public Nonce getNonce() {
      return nonce;
    }

    /** Single-use nonce dedup, the stronger tier (requires a store). Off by default. */
    public static class Nonce {

      private boolean enabled = false;
      private String header = "X-Nonce";

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      public String getHeader() {
        return header;
      }

      public void setHeader(String header) {
        this.header = header;
      }
    }
  }

  /** Rate limiting. Off by default. */
  public static class RateLimit {

    /** Whether the rate-limit filter is registered. */
    private boolean enabled = false;

    /** Policy name, echoed in the RateLimit headers. */
    private String policyName = "default";

    /** Maximum requests per window. */
    private long limit = 100;

    /** Window length. */
    private Duration window = Duration.ofMinutes(1);

    /** How to derive the bucket key: {@code ip} or {@code header}. */
    private String key = "ip";

    /** Header to read when {@code key=header}. */
    private String keyHeader = "X-Api-Key";

    /** Which headers to emit: {@code ietf}, {@code legacy}, or {@code both}. */
    private String headers = "ietf";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getPolicyName() {
      return policyName;
    }

    public void setPolicyName(String policyName) {
      this.policyName = policyName;
    }

    public long getLimit() {
      return limit;
    }

    public void setLimit(long limit) {
      this.limit = limit;
    }

    public Duration getWindow() {
      return window;
    }

    public void setWindow(Duration window) {
      this.window = window;
    }

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public String getKeyHeader() {
      return keyHeader;
    }

    public void setKeyHeader(String keyHeader) {
      this.keyHeader = keyHeader;
    }

    public String getHeaders() {
      return headers;
    }

    public void setHeaders(String headers) {
      this.headers = headers;
    }
  }
}

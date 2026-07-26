package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.error.ApiError;
import java.net.URI;
import org.springframework.http.ProblemDetail;

/**
 * Translates the framework-free {@link ApiError} value model into Spring's {@link ProblemDetail}
 * wire type. The extension members ({@code code}, {@code traceId}, {@code errors}) become
 * problem-detail properties; the standard members map one-to-one. Keeping this translation here is
 * what lets the {@code -web} tier stay independent of Spring.
 */
final class ProblemDetailMapper {

  private ProblemDetailMapper() {}

  static ProblemDetail toProblemDetail(ApiError error) {
    ProblemDetail detail = ProblemDetail.forStatus(error.status());
    detail.setType(URI.create(error.type()));
    detail.setTitle(error.title());
    if (error.detail() != null) {
      detail.setDetail(error.detail());
    }
    if (error.instance() != null) {
      detail.setInstance(URI.create(error.instance()));
    }
    if (error.code() != null) {
      detail.setProperty("code", error.code());
    }
    if (error.requestId() != null) {
      detail.setProperty("requestId", error.requestId());
    }
    if (error.traceId() != null) {
      detail.setProperty("traceId", error.traceId());
    }
    if (!error.errors().isEmpty()) {
      detail.setProperty("errors", error.errors());
    }
    return detail;
  }
}

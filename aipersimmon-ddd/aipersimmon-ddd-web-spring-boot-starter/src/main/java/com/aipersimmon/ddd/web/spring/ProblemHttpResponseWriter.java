package com.aipersimmon.ddd.web.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an RFC 9457 problem body straight to the servlet response. Filters run before the {@code
 * DispatcherServlet}, so exceptions they raise never reach the {@code @RestControllerAdvice}; they
 * use this to emit the same {@code application/problem+json} shape (type/title/status + traceId)
 * that the advice produces, keeping error responses uniform across the request lifecycle.
 */
public class ProblemHttpResponseWriter {

  private final ObjectMapper objectMapper;

  public ProblemHttpResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  void write(
      HttpServletResponse response,
      HttpStatus status,
      String type,
      String detail,
      Map<String, String> extraHeaders)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type == null ? "about:blank" : type);
    body.put("title", status.getReasonPhrase());
    body.put("status", status.value());
    if (detail != null) {
      body.put("detail", detail);
    }
    String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
    if (requestId != null) {
      body.put("requestId", requestId);
    }
    String traceId = MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
    if (traceId != null) {
      body.put("traceId", traceId);
    }
    body.put("errors", List.of());

    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (extraHeaders != null) {
      extraHeaders.forEach(response::setHeader);
    }
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}

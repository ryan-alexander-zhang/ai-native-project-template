package com.aipersimmon.ddd.web.spring;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body up front so it can be read twice: once by a filter (to verify a
 * signature over it) and again by the controller. The body is read fully in the constructor and
 * served from memory on each access.
 */
class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

  private final byte[] body;

  CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
    super(request);
    this.body = request.getInputStream().readAllBytes();
  }

  String bodyAsString() {
    return new String(body, StandardCharsets.UTF_8);
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream buffer = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return buffer.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException();
      }

      @Override
      public int read() {
        return buffer.read();
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }
}

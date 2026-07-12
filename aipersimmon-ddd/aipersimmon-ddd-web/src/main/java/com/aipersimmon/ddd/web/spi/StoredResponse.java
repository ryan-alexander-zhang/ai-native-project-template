package com.aipersimmon.ddd.web.spi;

import java.util.Map;

/**
 * A captured HTTP response, stored by an {@link IdempotencyStore} so a replayed
 * request with the same idempotency key can be answered with the original outcome.
 *
 * @param status  the HTTP status code of the first response
 * @param body    the response body bytes (may be empty, never null)
 * @param headers the response headers to replay; never null (copied defensively)
 */
public record StoredResponse(int status, byte[] body, Map<String, String> headers) {

    public StoredResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status (100-599), was " + status);
        }
        body = body == null ? new byte[0] : body.clone();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}

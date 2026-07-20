package com.aipersimmon.ddd.web;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.ApiError;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.aipersimmon.ddd.web.page.Cursor;
import com.aipersimmon.ddd.web.page.Page;
import com.aipersimmon.ddd.web.page.Slice;
import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.SignedRequest;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract-level tests for the framework-free web value objects and SPIs. */
class WebContractsTest {

    @Test
    void apiErrorFromDescriptorCarriesFieldsAndExtensions() {
        ApiError error = ApiError.from(
                new ProblemDescriptor("/problems/credit-exceeded", 422, "ordering.credit-exceeded.title"),
                "ordering.credit-exceeded",
                "Credit limit exceeded", "total 5000 exceeds remaining 3000",
                "/v1/orders", "req-abc", "trace-abc",
                List.of(new FieldError("/lines/0/qty", "out-of-range", "must be positive")));

        assertEquals("/problems/credit-exceeded", error.type());
        assertEquals(422, error.status());
        assertEquals("ordering.credit-exceeded", error.code());
        assertEquals("req-abc", error.requestId());
        assertEquals("trace-abc", error.traceId());
        assertEquals(1, error.errors().size());
    }

    @Test
    void problemDescriptorRejectsBlankTitleAndOutOfRangeStatusAndDefaultsBlankType() {
        assertThrows(IllegalArgumentException.class, () -> new ProblemDescriptor("/p", 422, " "));
        assertThrows(IllegalArgumentException.class, () -> new ProblemDescriptor("/p", 99, "t"));
        assertEquals("about:blank", new ProblemDescriptor("", 500, "problem.internal-error.title").typeUri());
    }

    @Test
    void apiErrorDefaultsBlankTypeToAboutBlankAndCopiesErrors() {
        List<FieldError> mutable = new ArrayList<>();
        mutable.add(new FieldError("f", "missing", null));
        ApiError error = new ApiError("", "Bad Request", 400, null, null, null, null, null, mutable);

        assertEquals("about:blank", error.type());
        mutable.clear();
        assertEquals(1, error.errors().size(), "errors must be copied defensively");
        assertThrows(UnsupportedOperationException.class, () -> error.errors().add(null));
    }

    @Test
    void apiErrorRejectsBlankTitleAndOutOfRangeStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiError("about:blank", " ", 400, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiError("about:blank", "x", 99, null, null, null, null, null, null));
    }

    @Test
    void apiExceptionCarriesErrorCodeAndErrors() {
        ErrorCode code = () -> "ordering.credit-exceeded";
        ApiException ex = new ApiException(code, "over limit",
                List.of(new FieldError("f", "c", "m")));
        assertSame(code, ex.errorCode());
        assertEquals("over limit", ex.getMessage());
        assertEquals(1, ex.errors().size());
        assertThrows(IllegalArgumentException.class, () -> new ApiException(null, "x"));
    }

    @Test
    void sliceIsCursorFirstAndReportsHasNext() {
        Slice<String> last = new Slice<>(List.of("a", "b"), null);
        assertFalse(last.hasNext());
        assertNull(last.nextCursor());

        Slice<String> more = new Slice<>(List.of("a"), Cursor.of("b3JkXzE="));
        assertTrue(more.hasNext());
        assertEquals("b3JkXzE=", more.nextCursor().value());
    }

    @Test
    void pageCarriesTotalsAndValidatesThem() {
        Page<String> page = new Page<>(List.of("a"), null, 42L, 5);
        assertEquals(42L, page.totalElements());
        assertEquals(5, page.totalPages());
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), null, -1L, 0));
    }

    @Test
    void cursorRejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> Cursor.of(" "));
    }

    @Test
    void storedResponseCopiesBodyDefensively() {
        byte[] body = {1, 2, 3};
        StoredResponse stored = new StoredResponse(200, body, Map.of("Content-Type", "application/json"));
        body[0] = 9;
        assertEquals(1, stored.body()[0], "body must be copied on the way in");
        stored.body()[0] = 9;
        assertEquals(1, stored.body()[0], "body must be copied on the way out");
    }

    @Test
    void rateLimitPolicyAndSignedRequestValidateInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitPolicy("default", 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SignedRequest(" ", "body", Instant.EPOCH, null));
        RateLimitPolicy policy = new RateLimitPolicy("default", 100, Duration.ofMinutes(1));
        assertEquals("default", policy.name());
    }
}

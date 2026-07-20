package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.ApiError;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.aipersimmon.ddd.web.error.ProblemRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 {@link ProblemDetail}s for the advices, centralising the one piece of
 * logic they share: turning the {@link ErrorCode} an exception carries from the domain
 * into a full wire representation.
 *
 * <ul>
 *   <li><strong>Coded</strong> exceptions (an {@link ApiException}, or a
 *       {@code -core}/{@code -application} exception carrying an {@link ErrorCode}) resolve
 *       through the {@link ProblemRegistry} to a {@link ProblemDescriptor} — a per-code
 *       override if registered, otherwise the code's category family. A coded error
 *       therefore always carries a meaningful {@code type} (never {@code about:blank}) and
 *       its stable {@code code} extension.</li>
 *   <li><strong>Code-less</strong> failures (a bare {@code DomainException}, bean
 *       validation, not-found, or an unexpected fault) fall back to {@code about:blank}
 *       with the caller-supplied status — {@code about:blank} is correct there, as the
 *       problem has no semantics beyond its HTTP status.</li>
 * </ul>
 */
class ProblemDetailFactory {

    private final ProblemRegistry registry;
    private final ProblemTitleResolver titleResolver;

    ProblemDetailFactory(ProblemRegistry registry, ProblemTitleResolver titleResolver) {
        this.registry = registry;
        this.titleResolver = titleResolver;
    }

    /** Build from an {@link ApiException} — the first-class catalogued path. */
    ProblemDetail fromApiException(ApiException ex) {
        return coded(ex.errorCode(), ex.getMessage(), ex.errors());
    }

    /** Build from an exception that may carry an {@link ErrorCode}. */
    ProblemDetail fromCoded(Optional<ErrorCode> code, String detail, HttpStatus fallback) {
        if (code.isPresent()) {
            return coded(code.get(), detail, List.of());
        }
        return simple(fallback, detail, List.of());
    }

    /** Render a coded error through the registry — always a resolved descriptor. */
    private ProblemDetail coded(ErrorCode code, String detail, List<FieldError> errors) {
        ProblemDescriptor descriptor = registry.resolve(code);
        ApiError error = ApiError.from(descriptor, code.code(), titleResolver.resolve(descriptor.titleKey()),
                detail, null, currentRequestId(), currentTraceId(), errors);
        return ProblemDetailMapper.toProblemDetail(error);
    }

    /** Build a plain (code-less) problem with an explicit status and optional field errors. */
    ProblemDetail simple(HttpStatus status, String detail, List<FieldError> errors) {
        ApiError error = new ApiError("about:blank", status.getReasonPhrase(), status.value(),
                detail, null, null, currentRequestId(), currentTraceId(), errors);
        return ProblemDetailMapper.toProblemDetail(error);
    }

    /** The per-request edge correlation id (set by the request-id filter). */
    private static String currentRequestId() {
        return MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
    }

    /** The real OpenTelemetry trace id, if the observability-otel module put it on the MDC. */
    private static String currentTraceId() {
        return MDC.get(RequestIdFilter.TRACE_ID_MDC_KEY);
    }
}

package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.ApiError;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import com.aipersimmon.ddd.web.error.ProblemType;
import com.aipersimmon.ddd.web.error.ProblemTypeRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 {@link ProblemDetail}s for the advices, centralising the one piece
 * of logic they share: turning the {@link ErrorCode} an exception carries from the
 * domain into a full wire representation. Resolution order:
 *
 * <ol>
 *   <li>the code resolves to a registered {@link ProblemType} → use its type, status,
 *       code and (resolved) title;</li>
 *   <li>coded but unregistered → the code's {@link ErrorCode#category()} chooses the
 *       status, and the code is still carried;</li>
 *   <li>no code → the caller's fallback status.</li>
 * </ol>
 */
class ProblemDetailFactory {

    private final ProblemTypeRegistry registry;
    private final ProblemTitleResolver titleResolver;

    ProblemDetailFactory(ProblemTypeRegistry registry, ProblemTitleResolver titleResolver) {
        this.registry = registry;
        this.titleResolver = titleResolver;
    }

    /** Build from an {@link ApiException} — the first-class catalogued path. */
    ProblemDetail fromApiException(ApiException ex) {
        ProblemType type = ex.problemType();
        ApiError error = ApiError.from(type, titleResolver.resolve(type), ex.getMessage(),
                null, currentTraceId(), ex.errors());
        return ProblemDetailMapper.toProblemDetail(error);
    }

    /** Build from an exception that may carry an {@link ErrorCode}. */
    ProblemDetail fromCoded(Optional<ErrorCode> code, String detail, HttpStatus fallback) {
        if (code.isPresent()) {
            Optional<ProblemType> registered = registry.byCode(code.get().code());
            if (registered.isPresent()) {
                ProblemType type = registered.get();
                ApiError error = ApiError.from(type, titleResolver.resolve(type), detail,
                        null, currentTraceId(), List.of());
                return ProblemDetailMapper.toProblemDetail(error);
            }
            HttpStatus status = statusForCategory(code.get().category());
            ApiError error = new ApiError("about:blank", status.getReasonPhrase(), status.value(),
                    detail, null, code.get().code(), currentTraceId(), List.of());
            return ProblemDetailMapper.toProblemDetail(error);
        }
        return simple(fallback, detail, List.of());
    }

    /** Build a plain problem with an explicit status and optional field errors. */
    ProblemDetail simple(HttpStatus status, String detail, List<FieldError> errors) {
        ApiError error = new ApiError("about:blank", status.getReasonPhrase(), status.value(),
                detail, null, null, currentTraceId(), errors);
        return ProblemDetailMapper.toProblemDetail(error);
    }

    private static HttpStatus statusForCategory(com.aipersimmon.ddd.core.error.ErrorCategory category) {
        return switch (category) {
            case DOMAIN_RULE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNEXPECTED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    }
}

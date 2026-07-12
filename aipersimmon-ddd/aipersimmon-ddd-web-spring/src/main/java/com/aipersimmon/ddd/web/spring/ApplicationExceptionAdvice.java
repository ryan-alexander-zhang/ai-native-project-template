package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.web.error.ApiError;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the optional {@code -application} {@link ApplicationException} — a use-case
 * orchestration failure that is not a domain-rule violation — to a 422 problem
 * response. Registered only when {@code aipersimmon-ddd-application} is on the
 * classpath, so consumers that do not depend on it inherit nothing.
 */
@RestControllerAdvice
public class ApplicationExceptionAdvice {

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplication(ApplicationException ex) {
        ApiError error = new ApiError("about:blank", HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage(), null, null,
                MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY), null);
        return ProblemDetailMapper.toProblemDetail(error);
    }
}

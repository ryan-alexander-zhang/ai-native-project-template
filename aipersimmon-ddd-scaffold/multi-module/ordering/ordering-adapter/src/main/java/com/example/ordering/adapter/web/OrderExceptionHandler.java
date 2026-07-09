package com.example.ordering.adapter.web;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain and input errors to HTTP responses. */
@RestControllerAdvice
public class OrderExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<String> onDomainRuleViolation(DomainException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> onBadInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}

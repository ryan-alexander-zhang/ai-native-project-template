package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class ConcurrencyTranslationCommandInterceptorTest {

    private record Ping() implements Command<Void> {
    }

    private final ConcurrencyTranslationCommandInterceptor interceptor =
            new ConcurrencyTranslationCommandInterceptor();

    @Test
    void translatesOptimisticLockFailureToConcurrencyConflict() {
        OptimisticLockingFailureException cause = new OptimisticLockingFailureException("stale");

        ConcurrencyConflictException ex = assertThrows(ConcurrencyConflictException.class,
                () -> interceptor.intercept(new Ping(), CommandContext.root("m1"), () -> {
                    throw cause;
                }));

        assertSame(cause, ex.getCause());
    }

    @Test
    void passesThroughWhenNoConflict() {
        assertEquals("ok",
                interceptor.intercept(new StringCommand(), CommandContext.root("m2"), () -> "ok"));
    }

    private record StringCommand() implements Command<String> {
    }
}

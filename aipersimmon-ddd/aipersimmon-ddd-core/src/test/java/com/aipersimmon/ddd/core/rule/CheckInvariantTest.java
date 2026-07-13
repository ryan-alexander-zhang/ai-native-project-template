package com.aipersimmon.ddd.core.rule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import org.junit.jupiter.api.Test;

class CheckInvariantTest {

    /** A minimal error-code enum, the way a bounded context would define one. */
    private enum SampleCode implements ErrorCode {
        CREDIT_EXCEEDED;

        @Override
        public String code() {
            return "sample.credit-exceeded";
        }
    }

    private record CreditRule(boolean broken) implements Invariant {
        @Override
        public boolean isBroken() {
            return broken;
        }

        @Override
        public String message() {
            return "credit limit exceeded";
        }

        @Override
        public ErrorCode errorCode() {
            return SampleCode.CREDIT_EXCEEDED;
        }
    }

    private static final class SampleAggregate extends AbstractAggregateRoot<String> {
        private final String id;

        SampleAggregate(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        void enforce(Invariant invariant) {
            checkInvariant(invariant);
        }
    }

    @Test
    void checkInvariant_doesNothing_whenInvariantHolds() {
        SampleAggregate aggregate = new SampleAggregate("a-1");
        assertDoesNotThrow(() -> aggregate.enforce(new CreditRule(false)));
    }

    @Test
    void checkInvariant_throws_whenInvariantBroken_carryingMessageAndCode() {
        SampleAggregate aggregate = new SampleAggregate("a-1");

        InvariantViolationException ex =
                assertThrows(InvariantViolationException.class,
                        () -> aggregate.enforce(new CreditRule(true)));

        assertEquals("credit limit exceeded", ex.getMessage());
        assertTrue(ex.errorCode().isPresent());
        assertSame(SampleCode.CREDIT_EXCEEDED, ex.errorCode().get());
        assertEquals("sample.credit-exceeded", ex.errorCode().get().code());
        assertEquals(ErrorCategory.DOMAIN_RULE, ex.errorCode().get().category());
    }
}

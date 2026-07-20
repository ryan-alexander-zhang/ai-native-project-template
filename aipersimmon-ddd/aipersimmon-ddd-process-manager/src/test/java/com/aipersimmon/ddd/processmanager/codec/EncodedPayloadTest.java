package com.aipersimmon.ddd.processmanager.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** {@link EncodedPayload} is a value object: equality is by type and byte content, not array identity. */
class EncodedPayloadTest {

    private static final PayloadType TYPE = new PayloadType("test.thing", 1);

    @Test
    void twoEncodingsOfEqualBytesAreEqual() {
        EncodedPayload a = new EncodedPayload(TYPE, "hello".getBytes(StandardCharsets.UTF_8));
        EncodedPayload b = new EncodedPayload(TYPE, "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(a, b, "equal type and byte content means equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal values have equal hash codes");
    }

    @Test
    void differsOnContentOrType() {
        EncodedPayload base = new EncodedPayload(TYPE, "hello".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(base, new EncodedPayload(TYPE, "world".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(base, new EncodedPayload(new PayloadType("test.thing", 2),
                "hello".getBytes(StandardCharsets.UTF_8)));
    }
}

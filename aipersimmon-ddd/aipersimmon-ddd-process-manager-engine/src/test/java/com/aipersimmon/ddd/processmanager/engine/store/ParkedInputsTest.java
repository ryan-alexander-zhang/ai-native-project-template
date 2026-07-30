package com.aipersimmon.ddd.processmanager.engine.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Two small conventions that are nonetheless persisted, so getting them wrong is a data problem
 * rather than a code one: how a parked input's replay is named, and how codec bytes reach a text
 * column.
 */
class ParkedInputsTest {

  @Test
  void aReplayRunsUnderADerivedIdBecauseTheOriginalWouldBeSeenAsADuplicate() {
    // UNIQUE(instance_id, input_message_id) already holds the park row, so replaying under the
    // same id would collapse into a no-op — which is precisely what the park row is.
    assertEquals("parked:msg-1", ParkedInputs.replayIdFor("msg-1"));
  }

  @Test
  void aReplayIsRecognisableSoTheRuntimeDoesNotParkOneAgain() {
    assertTrue(ParkedInputs.isReplayId("parked:msg-1"));
    assertFalse(ParkedInputs.isReplayId("msg-1"), "a fresh arrival is not a replay");
    assertFalse(ParkedInputs.isReplayId(null), "and neither is nothing at all");
  }

  @Test
  void aReplayNamesTheInputItCameFrom() {
    assertEquals("msg-1", ParkedInputs.originalIdOf(ParkedInputs.replayIdFor("msg-1")));
  }

  @Test
  void replayingAReplayWouldChainThePrefix() {
    // The runtime refuses to park a replay for exactly this reason: each round would add a prefix
    // until the id outgrew its column.
    String twice = ParkedInputs.replayIdFor(ParkedInputs.replayIdFor("msg-1"));

    assertEquals("parked:parked:msg-1", twice);
    assertTrue(
        ParkedInputs.isReplayId(ParkedInputs.originalIdOf(twice)),
        "which is why recognising a replay has to come before parking one");
  }

  @Test
  void codecBytesSurviveTheTripThroughATextColumn() {
    byte[] payload = "any codec's bytes, JSON or not".getBytes(StandardCharsets.UTF_8);

    assertArrayEqualsRoundTrip(payload);
  }

  @Test
  void bytesThatAreNotTextAtAllSurviveToo() {
    // The point of encoding rather than storing raw: the four-table model has text columns, and a
    // codec may well be Avro or Protobuf.
    assertArrayEqualsRoundTrip(new byte[] {0, -1, 127, -128, 10, 13});
  }

  @Test
  void anEmptyPayloadIsStillAPayload() {
    assertArrayEqualsRoundTrip(new byte[0]);
  }

  private static void assertArrayEqualsRoundTrip(byte[] payload) {
    assertEquals(
        java.util.Arrays.toString(payload),
        java.util.Arrays.toString(Payloads.fromText(Payloads.toText(payload))));
  }
}

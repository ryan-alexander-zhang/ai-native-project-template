/**
 * S27: deleting is almost never deleting a row, and the three things it can be are not variations of one
 * another.
 *
 * <ol>
 *   <li><strong>Domain state.</strong> "Closed." The aggregate knows it, rules read it, it can be undone,
 *       and somebody can be told why. It is an ordinary field that happens to sound like a deletion.
 *   <li><strong>An infrastructure switch.</strong> MyBatis-Plus's {@code @TableLogic}: the row stops
 *       existing as far as the application is concerned, and the domain never hears about it. Nothing asks
 *       why, nothing un-does it, no rule branches on it.
 *   <li><strong>A compliance erasure.</strong> <em>Not a delete at all.</em> The row stays — its existence
 *       is a fact somebody may have to prove — and the personal columns are overwritten. Which is why this
 *       is the one that ripples: the same personal data is quoted in outbox payloads, referenced by audit
 *       rows, and adjacent to inbox keys, and each of those has a different right answer.
 * </ol>
 *
 * <p>The judgement between (1) and (2) is the first question, and the test for it is not technical: does
 * any business rule read the flag? does anyone un-set it? is there a legitimate query that wants the
 * deleted ones? Three noes means an infrastructure switch. One yes means it is domain state and belongs in
 * the state machine, where it can be explained.
 *
 * <p>Getting that judgement wrong has a mechanical consequence, not just a modelling one, and it is
 * measured here: a delete flag modelled as infrastructure but maintained by hand collides with the way the
 * library writes a whole aggregate root. See {@code ClearedColumnsTest}.
 */
package com.example.samples.s27;

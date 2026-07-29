/**
 * The parked-input worker that drains the replay queue. {@link
 * com.aipersimmon.ddd.processmanager.engine.replay.ParkedInputWorker} finds the active instances
 * that still owe a replay of an input parked during a suspension and hands each back to the
 * runtime's {@code handle} in arrival order, marking it replayed once its advance has committed.
 * Because the debt lives in the store rather than in the call that resumed the instance, a crash
 * mid-drain loses nothing: the next poll on any node continues it.
 */
package com.aipersimmon.ddd.processmanager.engine.replay;

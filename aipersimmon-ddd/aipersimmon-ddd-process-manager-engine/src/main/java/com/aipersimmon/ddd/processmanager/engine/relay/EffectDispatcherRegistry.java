package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a decoded effect to the one {@link ProcessEffectDispatcher} registered for its kind.
 * Construction fails fast if two dispatchers claim the same kind; a dispatch for a kind with no
 * dispatcher is an error.
 */
public final class EffectDispatcherRegistry {

  private final Map<ProcessEffectKind, ProcessEffectDispatcher> byKind =
      new EnumMap<>(ProcessEffectKind.class);

  public EffectDispatcherRegistry(Iterable<? extends ProcessEffectDispatcher> dispatchers) {
    for (ProcessEffectDispatcher dispatcher : dispatchers) {
      ProcessEffectDispatcher existing = byKind.put(dispatcher.kind(), dispatcher);
      if (existing != null) {
        throw new IllegalStateException(
            "two dispatchers registered for effect kind "
                + dispatcher.kind()
                + ": "
                + existing.getClass().getName()
                + " and "
                + dispatcher.getClass().getName());
      }
    }
  }

  public void dispatch(DecodedProcessEffect effect, CommandContext context) {
    ProcessEffectDispatcher dispatcher = byKind.get(effect.kind());
    if (dispatcher == null) {
      throw new IllegalStateException("no dispatcher registered for effect kind " + effect.kind());
    }
    dispatcher.dispatch(effect, context);
  }

  public boolean supports(ProcessEffectKind kind) {
    return byKind.containsKey(kind);
  }
}

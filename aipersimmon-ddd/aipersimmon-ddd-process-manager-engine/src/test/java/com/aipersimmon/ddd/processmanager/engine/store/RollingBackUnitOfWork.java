package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link ProcessUnitOfWork} over in-memory stores that actually rolls back.
 *
 * <p>A pass-through would be simpler and wrong. The engine writes and then does work that can throw
 * within the same unit — the deadline worker marks a timer {@code FIRED} before running the advance
 * it triggers, precisely so a terminal decision cannot later rewrite a timer that did fire — and
 * its retry path depends on the throw undoing that mark. Without a rollback here, that path looks
 * broken when it is not, and (the more dangerous direction) a future change that genuinely broke
 * atomicity would still pass.
 *
 * <p>Joining rather than nesting matches {@code REQUIRED}: an inner call takes part in the
 * transaction already running, so a failure rolls back the whole thing rather than only the inner
 * part. That is the same reason the engine refuses to retry inside somebody else's transaction.
 */
public final class RollingBackUnitOfWork implements ProcessUnitOfWork {

  private final List<Snapshottable> stores;
  private int depth;

  public RollingBackUnitOfWork(Snapshottable... stores) {
    this.stores = List.of(stores);
  }

  @Override
  public <R> R execute(Supplier<R> work) {
    if (depth > 0) {
      return work.get();
    }
    List<Object> saved = new ArrayList<>(stores.size());
    stores.forEach(store -> saved.add(store.snapshot()));
    depth++;
    try {
      return work.get();
    } catch (RuntimeException rollback) {
      for (int i = 0; i < stores.size(); i++) {
        stores.get(i).restore(saved.get(i));
      }
      throw rollback;
    } finally {
      depth--;
    }
  }

  @Override
  public boolean inExistingTransaction() {
    return depth > 0;
  }
}

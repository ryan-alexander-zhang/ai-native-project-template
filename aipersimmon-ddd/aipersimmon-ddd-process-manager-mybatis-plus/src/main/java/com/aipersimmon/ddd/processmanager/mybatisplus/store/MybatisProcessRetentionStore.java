package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import com.aipersimmon.ddd.processmanager.engine.store.ProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** The MyBatis-Plus retention store; the policy lives in {@link ProcessRetentionMapper}. */
public final class MybatisProcessRetentionStore implements ProcessRetentionStore {

  private final ProcessRetentionMapper mapper;

  public MybatisProcessRetentionStore(ProcessRetentionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<ProcessInstanceId> findPurgeable(Instant endedBefore, int limit) {
    return mapper.findPurgeable(Timestamp.from(endedBefore), limit).stream()
        .map(ProcessInstanceId::new)
        .toList();
  }

  @Override
  public int purge(List<ProcessInstanceId> instanceIds) {
    if (instanceIds.isEmpty()) {
      return 0;
    }
    List<String> ids = instanceIds.stream().map(ProcessInstanceId::value).toList();
    // Children first: an instance row with no transitions is a state the runtime refuses to answer
    // about, so a partial delete that left it behind would be worse than either extreme.
    mapper.deleteEffects(ids);
    mapper.deleteDeadlines(ids);
    mapper.deleteTransitions(ids);
    return mapper.deleteInstances(ids);
  }
}

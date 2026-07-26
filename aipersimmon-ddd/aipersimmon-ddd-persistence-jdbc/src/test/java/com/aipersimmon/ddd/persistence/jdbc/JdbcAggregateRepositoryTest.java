package com.aipersimmon.ddd.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The JDBC base routes a new aggregate to {@code insert} and an existing one to {@code update},
 * hands the subclass the version to check against, and turns "no rows updated" into a refusal
 * rather than a silent overwrite.
 */
class JdbcAggregateRepositoryTest {

  private record Renamed(String id) implements DomainEvent {}

  private static final class Thing extends AbstractAggregateRoot<String> {
    private final String id;

    private Thing(String id) {
      this.id = id;
    }

    static Thing brandNew(String id) {
      return new Thing(id);
    }

    static Thing loadedAt(String id, long version) {
      Thing thing = new Thing(id);
      thing.restoreVersion(version);
      return thing;
    }

    void rename() {
      registerEvent(new Renamed(id));
    }

    @Override
    public String id() {
      return id;
    }
  }

  private static final class CapturingDomainEvents implements DomainEvents {
    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      published.add(event);
    }
  }

  /** Stands in for a real repository: records which path ran and what version it was handed. */
  private static final class Things extends JdbcAggregateRepository<Thing> {
    private final int updateAffects;
    private final List<String> calls = new ArrayList<>();
    private Long expectedVersionSeen;

    Things(DomainEvents domainEvents, int updateAffects) {
      super(domainEvents);
      this.updateAffects = updateAffects;
    }

    void save(Thing thing) {
      saveAggregate(thing);
    }

    @Override
    protected int insert(Thing aggregate) {
      calls.add("insert");
      return 1;
    }

    @Override
    protected int update(Thing aggregate, long expectedVersion) {
      calls.add("update");
      expectedVersionSeen = expectedVersion;
      return updateAffects;
    }
  }

  @Test
  void aBrandNewAggregateIsInserted() {
    CapturingDomainEvents events = new CapturingDomainEvents();
    Things things = new Things(events, 1);
    Thing thing = Thing.brandNew("t-1");
    thing.rename();

    things.save(thing);

    assertThat(things.calls).containsExactly("insert");
    assertThat(thing.version()).isEqualTo(1L);
    assertThat(events.published).containsExactly(new Renamed("t-1"));
  }

  @Test
  void anExistingAggregateIsUpdatedAndHandedTheVersionToCheck() {
    Things things = new Things(new CapturingDomainEvents(), 1);
    Thing thing = Thing.loadedAt("t-1", 6L);

    things.save(thing);

    assertThat(things.calls).containsExactly("update");
    assertThat(things.expectedVersionSeen)
        .as("the base hands over the expected version, so omitting the predicate is conspicuous")
        .isEqualTo(6L);
    assertThat(thing.version()).isEqualTo(7L);
  }

  @Test
  void anUpdateAffectingNoRowIsRefused() {
    CapturingDomainEvents events = new CapturingDomainEvents();
    Things things = new Things(events, 0);
    Thing thing = Thing.loadedAt("t-1", 6L);
    thing.rename();

    assertThatThrownBy(() -> things.save(thing))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessageContaining("t-1")
        .hasMessageContaining("expected version 6");

    assertThat(events.published).as("a refused write publishes nothing").isEmpty();
    assertThat(thing.domainEvents()).hasSize(1);
    assertThat(thing.version()).isEqualTo(6L);
  }

  @Test
  void savingTwiceInOneTransactionChecksAgainstTheAdvancedVersion() {
    Things things = new Things(new CapturingDomainEvents(), 1);
    Thing thing = Thing.brandNew("t-1");

    things.save(thing);
    things.save(thing);

    assertThat(things.calls).containsExactly("insert", "update");
    assertThat(things.expectedVersionSeen).isEqualTo(1L);
  }
}

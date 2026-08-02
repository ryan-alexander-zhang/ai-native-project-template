package com.aipersimmon.ddd.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The JDBC base routes a new aggregate to {@code insert} and an existing one to {@code update},
 * hands the subclass the version to check against, and turns "no rows updated" into a refusal
 * rather than a silent overwrite.
 */
class JdbcAggregateRepositoryTest {

  private record Renamed(String id) implements DomainEvent {}

  private record ThingId(String value) implements com.aipersimmon.ddd.core.model.Identifier {}

  private static final class Thing extends AbstractAggregateRoot<ThingId> {
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
    public ThingId id() {
      return new ThingId(id);
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
    private int insertAffects = 1;
    private RuntimeException insertFailure;

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
      if (insertFailure != null) {
        throw insertFailure;
      }
      return insertAffects;
    }

    @Override
    protected int update(Thing aggregate, long expectedVersion) {
      calls.add("update");
      expectedVersionSeen = expectedVersion;
      return updateAffects;
    }
  }

  // saveAggregate refuses to run outside a transaction, and rightly: rows and events must commit
  // together. These cases are about the version protocol, not about transactionality, so they mark
  // the thread as transactional rather than standing up a database — the refusal itself is asserted
  // by writesOutsideATransactionAreRefused below.
  @BeforeEach
  void bindTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void unbindTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void writesOutsideATransactionAreRefused() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    CapturingDomainEvents events = new CapturingDomainEvents();
    Things things = new Things(events, 1);
    Thing thing = Thing.brandNew("t-1");
    thing.rename();

    assertThatThrownBy(() -> things.save(thing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no active transaction")
        .hasMessageContaining("t-1");

    assertThat(things.calls).as("nothing was written").isEmpty();
    assertThat(events.published).as("and nothing was published").isEmpty();
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
  void anInsertReportingZeroRowsIsRefusedAndPublishesNothing() {
    CapturingDomainEvents events = new CapturingDomainEvents();
    Things things = new Things(events, 1);
    things.insertAffects = 0;
    Thing thing = Thing.brandNew("t-1");
    thing.rename();

    assertThatThrownBy(() -> things.save(thing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zero rows")
        .hasMessageContaining("t-1");

    assertThat(events.published).as("an aggregate that was not saved publishes nothing").isEmpty();
    assertThat(thing.version()).as("and its version does not advance").isZero();
  }

  @Test
  void aDuplicateKeyOnInsertNamesBothPlausibleCauses() {
    CapturingDomainEvents events = new CapturingDomainEvents();
    Things things = new Things(events, 1);
    DuplicateKeyException cause = new DuplicateKeyException("dup");
    things.insertFailure = cause;
    Thing thing = Thing.brandNew("t-1");
    thing.rename();

    assertThatThrownBy(() -> things.save(thing))
        .isInstanceOf(DuplicateEntityException.class)
        .hasMessageContaining("t-1")
        .hasMessageContaining("concurrent creates")
        .hasMessageContaining("restoreVersion")
        .cause()
        .isSameAs(cause);

    assertThat(events.published).as("a refused create publishes nothing").isEmpty();
    assertThat(thing.version()).isZero();
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

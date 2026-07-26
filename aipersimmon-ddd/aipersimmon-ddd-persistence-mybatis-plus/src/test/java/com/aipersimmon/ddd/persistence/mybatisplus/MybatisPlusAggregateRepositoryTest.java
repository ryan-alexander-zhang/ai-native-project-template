package com.aipersimmon.ddd.persistence.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The base class writes under the loaded version, refuses a write that matches no row, and drains
 * the aggregate's events — the behaviour every consumer repository would otherwise re-implement,
 * and where getting it subtly wrong reintroduces lost updates.
 */
class MybatisPlusAggregateRepositoryTest {

  private record Renamed(String id) implements DomainEvent {}

  /** A minimal aggregate that can record an event on demand. */
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

  private static final class ThingRow implements VersionedRow {
    private Long version;

    @Override
    public Long getVersion() {
      return version;
    }

    @Override
    public void setVersion(Long version) {
      this.version = version;
    }
  }

  /** Collects what the repository published, so a refused write can be shown to publish nothing. */
  private static final class CapturingDomainEvents implements DomainEvents {
    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      published.add(event);
    }
  }

  private static final class Things extends MybatisPlusAggregateRepository<Thing, ThingRow> {
    private int childWrites;

    Things(BaseMapper<ThingRow> mapper, DomainEvents domainEvents) {
      super(mapper, domainEvents);
    }

    void save(Thing thing) {
      saveAggregate(thing);
    }

    @Override
    protected ThingRow toRow(Thing thing) {
      return new ThingRow();
    }

    @Override
    protected void saveChildren(Thing thing) {
      childWrites++;
    }
  }

  @SuppressWarnings("unchecked")
  private final BaseMapper<ThingRow> mapper = Mockito.mock(BaseMapper.class);

  private final CapturingDomainEvents events = new CapturingDomainEvents();
  private Things things;

  @BeforeEach
  void setUp() {
    things = new Things(mapper, events);
  }

  private Long versionPassedTo(java.util.function.Consumer<ArgumentCaptor<ThingRow>> verification) {
    ArgumentCaptor<ThingRow> captor = ArgumentCaptor.forClass(ThingRow.class);
    verification.accept(captor);
    return captor.getValue().getVersion();
  }

  @Test
  void aBrandNewAggregateIsInsertedAtVersionOneWithNoExistenceQuery() {
    when(mapper.insert(any(ThingRow.class))).thenReturn(1);
    Thing thing = Thing.brandNew("t-1");

    things.save(thing);

    assertThat(versionPassedTo(captor -> verify(mapper).insert(captor.capture()))).isEqualTo(1L);
    verify(mapper, never()).updateById(any(ThingRow.class));
    verify(mapper, never()).selectById(any());
    assertThat(thing.version()).as("in-memory version matches the stored row").isEqualTo(1L);
  }

  @Test
  void anExistingAggregateIsUpdatedUnderTheVersionItWasLoadedAt() {
    when(mapper.updateById(any(ThingRow.class))).thenReturn(1);
    Thing thing = Thing.loadedAt("t-1", 4L);

    things.save(thing);

    assertThat(versionPassedTo(captor -> verify(mapper).updateById(captor.capture())))
        .as("the loaded version is what @Version turns into WHERE version = ?")
        .isEqualTo(4L);
    verify(mapper, never()).insert(any(ThingRow.class));
    assertThat(thing.version()).isEqualTo(5L);
  }

  @Test
  void anUpdateThatMatchesNoRowIsRefused() {
    when(mapper.updateById(any(ThingRow.class))).thenReturn(0);
    Thing thing = Thing.loadedAt("t-1", 4L);
    thing.rename();

    assertThatThrownBy(() -> things.save(thing))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessageContaining("t-1")
        .hasMessageContaining("expected version 4");

    assertThat(events.published).as("a refused write publishes nothing").isEmpty();
    assertThat(thing.domainEvents()).as("and leaves the events for a retry").hasSize(1);
    assertThat(thing.version()).as("and does not advance the version").isEqualTo(4L);
    assertThat(things.childWrites).as("and does not write children").isZero();
  }

  @Test
  void aSuccessfulSaveWritesChildrenThenPublishesAndClearsEvents() {
    when(mapper.updateById(any(ThingRow.class))).thenReturn(1);
    Thing thing = Thing.loadedAt("t-1", 1L);
    thing.rename();

    things.save(thing);

    assertThat(things.childWrites).isEqualTo(1);
    assertThat(events.published).containsExactly(new Renamed("t-1"));
    assertThat(thing.domainEvents()).as("cleared, so a second save does not republish").isEmpty();
  }

  @Test
  void savingTwiceInOneTransactionChecksAgainstTheAdvancedVersion() {
    when(mapper.insert(any(ThingRow.class))).thenReturn(1);
    when(mapper.updateById(any(ThingRow.class))).thenReturn(1);
    Thing thing = Thing.brandNew("t-1");

    things.save(thing);
    assertThatCode(() -> things.save(thing)).doesNotThrowAnyException();

    assertThat(versionPassedTo(captor -> verify(mapper).updateById(captor.capture())))
        .as("the second save must neither re-insert nor check the stale version 0")
        .isEqualTo(1L);
  }
}

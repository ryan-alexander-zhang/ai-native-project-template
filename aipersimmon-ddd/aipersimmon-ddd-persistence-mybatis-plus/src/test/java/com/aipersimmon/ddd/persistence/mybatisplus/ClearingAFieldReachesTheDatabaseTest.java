package com.aipersimmon.ddd.persistence.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A command that empties a field has to reach the database.
 *
 * <p>It did not. MyBatis-Plus leaves a null field out of an entity update's {@code SET} clause —
 * correct for a partial update, wrong for saving an aggregate, where {@code toRow} maps the whole
 * root and null means the field is empty now. Everything reported success: the version moved so the
 * optimistic-lock check passed, the domain events published so downstream was told the change had
 * happened, and the old value came back on the next load. A command was accepted and half of it was
 * discarded, with no error anywhere.
 *
 * <p>Run against a real database on purpose. The defect is in generated SQL, so a mocked mapper
 * cannot see it — and the module's other test, which mocks the mapper, passed throughout.
 */
@SpringBootTest(classes = ClearingAFieldReachesTheDatabaseTest.TestApp.class)
class ClearingAFieldReachesTheDatabaseTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @org.mybatis.spring.annotation.MapperScan(
      basePackageClasses = ClearingAFieldReachesTheDatabaseTest.ThingMapper.class)
  static class TestApp {

    @org.springframework.context.annotation.Bean
    DomainEvents domainEvents() {
      return events -> {};
    }

    @org.springframework.context.annotation.Bean
    Things things(ThingMapper mapper, DomainEvents domainEvents) {
      return new Things(mapper, domainEvents);
    }
  }

  /** A row with a nullable column, which is all it takes to hit the trap. */
  @TableName("thing")
  public static class ThingRow implements VersionedRow {
    @TableId private String id;
    private String nickname;

    /** Configured ALWAYS by the application: already written, so it must not be written twice. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String motto;

    /** Configured NEVER by the application: a column it has said to leave alone. */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String secret;

    @Version private Long version;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getNickname() {
      return nickname;
    }

    public void setNickname(String nickname) {
      this.nickname = nickname;
    }

    public String getMotto() {
      return motto;
    }

    public void setMotto(String motto) {
      this.motto = motto;
    }

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    @Override
    public Long getVersion() {
      return version;
    }

    @Override
    public void setVersion(Long version) {
      this.version = version;
    }
  }

  @Mapper
  public interface ThingMapper extends BaseMapper<ThingRow> {}

  private record NicknameCleared(String id) implements DomainEvent {}

  /** An aggregate whose nickname is optional — the domain's way of saying "it can be cleared". */
  static final class Thing extends AbstractAggregateRoot<String> {
    private final String id;
    private String nickname;

    private Thing(String id, String nickname) {
      this.id = id;
      this.nickname = nickname;
    }

    static Thing named(String id, String nickname) {
      return new Thing(id, nickname);
    }

    static Thing loaded(String id, String nickname, long version) {
      Thing thing = new Thing(id, nickname);
      thing.restoreVersion(version);
      return thing;
    }

    void clearNickname() {
      this.nickname = null;
      registerEvent(new NicknameCleared(id));
    }

    @Override
    public String id() {
      return id;
    }
  }

  static final class Things extends MybatisPlusAggregateRepository<Thing, ThingRow> {
    Things(ThingMapper mapper, DomainEvents domainEvents) {
      super(mapper, domainEvents);
    }

    void save(Thing thing) {
      saveAggregate(thing);
    }

    @Override
    protected ThingRow toRow(Thing thing) {
      ThingRow row = new ThingRow();
      row.setId(thing.id);
      row.setNickname(thing.nickname);
      row.setMotto("carpe diem");
      return row;
    }
  }

  @Autowired Things things;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void resetTable() {
    jdbc.execute("DROP TABLE IF EXISTS thing");
    jdbc.execute(
        "CREATE TABLE thing (id VARCHAR(64) PRIMARY KEY, nickname VARCHAR(64), "
            + "motto VARCHAR(64), secret VARCHAR(64), version BIGINT NOT NULL)");
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private String columnOf(String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM thing WHERE id = 't-1'", String.class);
  }

  @Test
  void clearingAFieldClearsTheColumn() {
    inTransaction(() -> things.save(Thing.named("t-1", "Bob")));
    assertThat(columnOf("nickname")).isEqualTo("Bob");

    Thing loaded = Thing.loaded("t-1", "Bob", 1);
    loaded.clearNickname();
    inTransaction(() -> things.save(loaded));

    // Before the fix this read "Bob": the assignment was dropped from the SET clause, the update
    // reported one row changed, and nothing anywhere said the command had been half-applied.
    assertThat(columnOf("nickname")).isNull();
  }

  @Test
  void theVersionStillMovesAndStillGuardsTheWrite() {
    inTransaction(() -> things.save(Thing.named("t-1", "Bob")));

    Thing loaded = Thing.loaded("t-1", "Bob", 1);
    loaded.clearNickname();
    inTransaction(() -> things.save(loaded));

    assertThat(jdbc.queryForObject("SELECT version FROM thing WHERE id = 't-1'", Long.class))
        .isEqualTo(2L);
    // And a writer working from the version it already spent is still refused: rewriting the SET
    // clause must not have cost the predicate that makes this a check rather than a hope.
    Thing stale = Thing.loaded("t-1", "Bob", 1);
    stale.clearNickname();
    assertThatThrownBy(() -> inTransaction(() -> things.save(stale)))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void aColumnTheApplicationConfiguredAlwaysIsWrittenOnceAndNotTwice() {
    inTransaction(() -> things.save(Thing.named("t-1", "Bob")));

    Thing loaded = Thing.loaded("t-1", "Bob", 1);
    loaded.clearNickname();
    inTransaction(() -> things.save(loaded));

    // The entity's own SET already carries an ALWAYS column. Adding it again would emit
    // `SET motto = ?, motto = null` — which MySQL accepts and PostgreSQL rejects outright, so a
    // library that got this wrong would work in one test environment and fail in another.
    assertThat(columnOf("motto")).isEqualTo("carpe diem");
  }

  @Test
  void aColumnTheApplicationConfiguredNeverIsStillLeftAlone() {
    inTransaction(() -> things.save(Thing.named("t-1", "Bob")));
    jdbc.update("UPDATE thing SET secret = 'kept' WHERE id = 't-1'");

    Thing loaded = Thing.loaded("t-1", "Bob", 1);
    loaded.clearNickname();
    inTransaction(() -> things.save(loaded));

    // NEVER is the application saying "this column is not yours to write". Forcing it to null
    // because the row object happens to hold null there would destroy data on its say-so.
    assertThat(columnOf("secret")).isEqualTo("kept");
  }

  @Test
  void aFieldThatWasNotClearedIsStillWritten() {
    inTransaction(() -> things.save(Thing.named("t-1", "Bob")));

    Thing loaded = Thing.loaded("t-1", "Bob", 1);
    inTransaction(() -> things.save(Thing.loaded("t-1", "Alice", 1)));

    assertThat(columnOf("nickname")).isEqualTo("Alice");
    assertThat(loaded.id()).isEqualTo("t-1");
  }
}

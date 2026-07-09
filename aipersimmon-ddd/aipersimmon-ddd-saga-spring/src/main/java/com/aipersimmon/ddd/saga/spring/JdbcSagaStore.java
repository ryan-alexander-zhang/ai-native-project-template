package com.aipersimmon.ddd.saga.spring;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;
import com.aipersimmon.ddd.saga.SagaStore;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Abstract {@link SagaStore} over a single relational table. It owns the parts that
 * are the same for every saga — the correlation-id lookup, the insert of a new
 * instance, and the version-checked update that guards against two events advancing
 * the same instance at once — and leaves only the mapping between a concrete saga's
 * state and the stored columns to the subclass, so persistence mapping stays
 * explicit rather than reflective.
 *
 * <p>The table has columns {@code correlation_id} (primary key), {@code status},
 * {@code version}, and {@code data} (a text column the subclass fills with the
 * saga's flow data). A newly started saga has {@link SagaState#version()} {@code 0}
 * and is inserted at version {@code 1}; each successful save increments the version,
 * and a save whose expected version no longer matches the stored row raises
 * {@link OptimisticLockingFailureException}.
 *
 * @param <S> the concrete saga state type this store persists
 */
public abstract class JdbcSagaStore<S extends SagaState> implements SagaStore<S> {

    /** Default table name; a sample DDL ships on the classpath. */
    public static final String DEFAULT_TABLE = "aipersimmon_saga";

    private final JdbcTemplate jdbc;
    private final String table;

    protected JdbcSagaStore(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_TABLE);
    }

    protected JdbcSagaStore(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public Optional<S> find(String correlationId) {
        return jdbc.query(
                "SELECT correlation_id, status, version, data FROM " + table
                        + " WHERE correlation_id = ?",
                (rs, rowNum) -> mapRow(
                        rs.getString("correlation_id"),
                        SagaStatus.valueOf(rs.getString("status")),
                        rs.getLong("version"),
                        rs.getString("data")),
                correlationId).stream().findFirst();
    }

    @Override
    public void save(S saga) {
        String data = serializeData(saga);
        if (saga.version() == 0) {
            jdbc.update(
                    "INSERT INTO " + table + " (correlation_id, status, version, data)"
                            + " VALUES (?, ?, ?, ?)",
                    saga.correlationId(), saga.status().name(), 1L, data);
            return;
        }
        int updated = jdbc.update(
                "UPDATE " + table + " SET status = ?, version = version + 1, data = ?"
                        + " WHERE correlation_id = ? AND version = ?",
                saga.status().name(), data, saga.correlationId(), saga.version());
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "Saga " + saga.correlationId() + " was modified concurrently"
                            + " (expected version " + saga.version() + ")");
        }
    }

    /**
     * Reconstruct a saga instance from a stored row. Pass {@code version} to the
     * {@link SagaState} rehydrating constructor so a later {@link #save(SagaState)}
     * can check it.
     */
    protected abstract S mapRow(String correlationId, SagaStatus status, long version, String data);

    /** Serialize the saga's flow data into the value stored in the {@code data} column. */
    protected abstract String serializeData(S saga);
}

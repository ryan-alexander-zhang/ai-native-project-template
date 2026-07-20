package com.aipersimmon.ddd.processmanager.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Per-instance {@code transition_seq} pins parked-input replay to insertion order. */
class JdbcProcessTransitionStoreTest {

    // A fixed clock makes both parked rows share one created_at, so the old
    // (created_at, transition_id) key would fall back to the random transition_id.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);
    private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");

    private JdbcProcessTransitionStore transitions;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
                .addScript("classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
                .build();
        transitions = new JdbcProcessTransitionStore(new JdbcTemplate(dataSource));
    }

    @Test
    void replaysParkedInputsInInsertionOrderEvenWhenCreatedAtTiesAndIdsSortReverse() {
        Instant sameInstant = CLOCK.instant();
        // The two transition ids sort in the reverse of insertion order, so any tie-break on the
        // random id would flip the replay order under the equal created_at.
        appendParked("zzz-transition", "input-first", sameInstant);
        appendParked("aaa-transition", "input-second", sameInstant);

        List<JdbcProcessTransitionStore.ParkedInput> parked = transitions.findParkedInputs(INSTANCE);

        assertEquals(
                List.of("input-first", "input-second"),
                parked.stream().map(JdbcProcessTransitionStore.ParkedInput::inputMessageId).toList(),
                "parked inputs replay in insertion order, not random transition_id order");
    }

    private void appendParked(String transitionId, String inputMessageId, Instant at) {
        transitions.append(new ProcessTransitionInsert(
                transitionId,
                INSTANCE,
                inputMessageId,
                "test.input",
                1,
                inputMessageId.getBytes(StandardCharsets.UTF_8),
                Optional.of(ProcessLifecycle.SUSPENDED),
                ProcessLifecycle.SUSPENDED,
                Optional.of(new ProcessStep("S1")),
                new ProcessStep("S1"),
                new DecisionCode("parked"),
                "PARKED",
                "corr-1"), at);
    }
}

package com.aipersimmon.ddd.saga.spring;

import com.aipersimmon.ddd.saga.DeadlineHandler;
import com.aipersimmon.ddd.saga.DeadlineScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Auto-configures the in-process {@link SchedulingDeadlineScheduler} when the
 * application supplies a {@link DeadlineHandler} bean (its saga's timeout logic),
 * providing a single-threaded {@link TaskScheduler} if none is already defined. No
 * {@code SagaStore} is auto-configured: a bounded context subclasses
 * {@link JdbcSagaStore} for its own saga and registers that as a bean.
 */
@AutoConfiguration
public class AipersimmonDddSagaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskScheduler.class)
    public TaskScheduler aipersimmonSagaTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("aipersimmon-saga-deadline-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnBean(DeadlineHandler.class)
    @ConditionalOnMissingBean(DeadlineScheduler.class)
    public DeadlineScheduler deadlineScheduler(TaskScheduler taskScheduler,
                                               ObjectProvider<DeadlineHandler> handler) {
        // Resolve the handler lazily so a process manager can both arm deadlines
        // through this scheduler and be the handler, without a construction cycle.
        return new SchedulingDeadlineScheduler(taskScheduler, handler::getObject);
    }
}

package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.CommandBus;
import com.acme.samples.s2.shared.CommandHandler;
import com.acme.samples.s2.shared.DomainEvents;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Assembles the CommandBus decorator chain (analysis-00005 §5.1):
 * {@code Logging → Validation → Transaction(UnitOfWork) → SimpleCommandBus}.
 * Validation runs before the transaction opens; the Transaction decorator owns the
 * UnitOfWork (tx + domain-event dispatch). Switching the chain is a wiring change
 * here only — callers depend solely on the {@link CommandBus} port.
 */
@Configuration
public class CommandBusConfig {

    @Bean
    CommandBus commandBus(List<CommandHandler<?, ?>> handlers,
                          PlatformTransactionManager txManager,
                          DomainEvents domainEvents,
                          ThreadLocalAggregateChanges changes,
                          Validator validator) {
        CommandBus core = new SimpleCommandBus(handlers);
        CommandBus transactional = new TransactionalCommandBus(core, txManager, domainEvents, changes);
        CommandBus validating = new ValidatingCommandBus(transactional, validator);
        return new LoggingCommandBus(validating);
    }
}

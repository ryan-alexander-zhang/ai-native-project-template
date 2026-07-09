package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ResolvableType;

/**
 * A {@link QueryBus} that routes each query to the single {@link QueryHandler}
 * registered for its type, indexed by the query type resolved from the handler's
 * generic signature. There is no interceptor chain or transaction here: a query
 * neither changes state nor records events.
 */
public class RegistryQueryBus implements QueryBus {

    private final Map<Class<?>, QueryHandler<?, ?>> handlers = new HashMap<>();

    public RegistryQueryBus(List<QueryHandler<?, ?>> handlers) {
        for (QueryHandler<?, ?> handler : handlers) {
            Class<?> queryType = queryTypeOf(handler);
            QueryHandler<?, ?> existing = this.handlers.put(queryType, handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Two query handlers registered for " + queryType.getName()
                                + ": " + existing.getClass().getName()
                                + " and " + handler.getClass().getName());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R ask(Query<R> query) {
        QueryHandler<Query<R>, R> handler =
                (QueryHandler<Query<R>, R>) handlers.get(query.getClass());
        if (handler == null) {
            throw new IllegalStateException(
                    "No query handler registered for " + query.getClass().getName());
        }
        return handler.handle(query);
    }

    private static Class<?> queryTypeOf(QueryHandler<?, ?> handler) {
        Class<?> type = ResolvableType.forInstance(handler)
                .as(QueryHandler.class)
                .getGeneric(0)
                .resolve();
        if (type == null) {
            throw new IllegalStateException(
                    "Cannot resolve the query type of handler "
                            + handler.getClass().getName()
                            + "; declare it with a concrete Query type parameter");
        }
        return type;
    }
}

# aipersimmon-ddd-operation-log-cqrs-spring

The CQRS/Spring capture layer for the Operation Log component. Add it (with a storage backend) and
every write command carrying `@OperationLog` — or a hand-written `OperationLogDefinition` — is
recorded as a business-readable operation-log row, on the command bus, with no changes to your
handlers.

## What a consumer wires

You need **four** things. The interceptors, template engine, annotation scanning, and transaction
coordination are auto-configured; you do not touch them.

1. **A storage backend** (choose one), on the classpath of your bootstrap/composition module:
   - `aipersimmon-ddd-operation-log-jdbc`, or
   - `aipersimmon-ddd-operation-log-mybatis-plus`.

   The backend supplies the `OperationLogSink` and ships the schema (the three-dialect DDL lives in
   the engine and is picked up by `aipersimmon-ddd-flyway` when you list `operation-log` under
   `aipersimmon.ddd.flyway.components`).

2. **This module** (`aipersimmon-ddd-operation-log-cqrs-spring`) on the same classpath — it
   registers the capture interceptors once a sink is present.

3. **The two trusted resolvers**, as beans in your composition root. They tell the capture layer
   *who* performed the operation and *which tenant* it belongs to — resolved from a trusted scope
   (security context, request scope), **never** from the command payload:

   ```java
   @Configuration(proxyBeanMethods = false)
   class OperationLogConfig {

     @Bean
     OperationActorResolver operationActorResolver() {
       // A real app resolves the caller from its SecurityContext / request scope.
       return () -> Actor.system("my-service");
     }

     @Bean
     OperationTenantResolver operationTenantResolver() {
       // Return the GLOBAL normalization when multi-tenancy is disabled.
       return () -> "GLOBAL";
     }
   }
   ```

   These are **required** once a backend is present: the capture layer fails closed (refuses to
   start) rather than record a missing or forged identity. If you forget them, startup fails with an
   actionable message from `MissingOperationLogResolverFailureAnalyzer` telling you exactly which
   bean to add. Applications that use only the direct Operation Log API (no annotations/definitions)
   do not need this module or the resolvers.

4. **`@OperationLog` on your write commands** (the annotation is in the framework-free
   `aipersimmon-ddd-operation-log` core, so annotate commands without dragging Spring onto the
   application tier):

   ```java
   @OperationLog(
       code = "ordering.order.place",
       targetType = "Customer",
       targetId = "${input.customerId}",
       success = "Placed order ${resultProjection} for customer ${input.customerId}",
       failure = "Placing order for customer ${input.customerId} failed: ${failure.code} (${failure.safeSummary})")
   public record PlaceOrder(String customerId, ...) implements Command<String> {}
   ```

## Templates (restricted, not SpEL)

`targetId`, `success`, and `failure` are compiled and validated at startup. They are a restricted
property-path grammar over fixed roots — not SpEL — so they cannot read repositories, call methods
with arguments, or reach the bean factory.

| Attribute | Roots available | Notes |
|-----------|-----------------|-------|
| `targetId` | `input` | Renders from the command only (it is captured *before* the handler runs). |
| `success`  | `input`, `resultProjection` | `resultProjection` is the handler result; use `${resultProjection}` for a bare value. |
| `failure`  | `input`, `failure` | `failure` is the sanitized `ClassifiedFailure` (`code`, `category`, `safeSummary`) — never the raw exception. |

Functions: `mask(..)`, `truncate(.., n)`, `defaultValue(.., 'x')`. Command and result types must be
`public` (the engine reflects no-arg accessors only, never `setAccessible`).

## Outcome and completion

Each row carries an orthogonal **outcome** (`SUCCEEDED` / `REJECTED` / `FAILED`) and **transaction
completion** (`COMMITTED` / `ROLLED_BACK` / `NOT_STARTED` / `UNKNOWN`). A committed success is
recorded inside the business transaction; a failure is recorded in an independent transaction so the
row survives a rollback, and the original business exception is always rethrown unchanged.
`recordFailure` (default `true`) controls whether the failure path records a row.

See `docs/design/design-00008-operation-log-component.md` and
`docs/decision/decision-00017-operation-log-component-boundaries.md` for the full design.

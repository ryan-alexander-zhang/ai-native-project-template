# AiPersimmon DDD

> `com.aipersimmon.ddd` · `0.1.0-SNAPSHOT` · Java 21 · Spring Boot 3

DDD building blocks for services that have to stay correct under concurrency and at-least-once
delivery. The tactical model (`core`, `cqrs`, `integration`, …) is framework-free; Spring,
MyBatis-Plus, JDBC, Kafka and Redis live in separate pluggable modules you add only if you use them.

**Three guides, in the order you need them**

| | |
| --- | --- |
| this file | get something running |
| [CHOOSING-MODULES.md](CHOOSING-MODULES.md) | which dependency for which problem |
| [CONFIGURATION.md](CONFIGURATION.md) | every `aipersimmon.ddd.*` property and its default |

The module map and dependency rules are in [../ARCHITECTURE.md](../ARCHITECTURE.md); a complete
working service is in [../aipersimmon-ddd-scaffold/multi-module](../aipersimmon-ddd-scaffold/multi-module).

---

## Quick start

### 1. Two dependencies

```xml
<dependencyManagement>
  <dependencies>
    <!-- Import first: this BOM carries the OpenTelemetry core line the optional
         observability starter needs, which Spring Boot manages to an older one.
         It manages nothing else of anyone else's — see "What this BOM does not do" below. -->
    <dependency>
      <groupId>com.aipersimmon.ddd</groupId>
      <artifactId>aipersimmon-ddd-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <!-- MyBatis-Plus is your choice of persistence, not ours: neither Spring Boot nor
         this BOM versions it. Import its BOM (or pin the version) yourself. -->
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-bom</artifactId>
      <version>3.5.15</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.aipersimmon.ddd</groupId>
    <artifactId>aipersimmon-ddd-starter-mybatis-plus</artifactId>
  </dependency>
  <dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
  </dependency>
</dependencies>
```

> **What this BOM does not do: choose your Spring Boot version.** It manages the
> `com.aipersimmon.ddd` modules and three coordinates this library does not work without (the
> OpenTelemetry core line, and the two OpenAPI artifacts nothing else manages) — 72 entries in
> total. It deliberately has no parent POM, because an imported BOM contributes its *effective*
> model: while it inherited one, importing it brought 1626 managed coordinates along. Since it has
> to be imported *before* `spring-boot-dependencies` and the first import wins, that silently
> overruled your own Spring Boot version — in precisely the arrangement above. Bring your own
> `spring-boot-dependencies`; this BOM will not argue with it.

That is CQRS, in-process events, time-ordered UUIDv7 ids, RFC 9457 error responses, version-checked
aggregate repositories, a transactional outbox, a consumer inbox, the durable process manager, the
operation log, multi-tenancy and the schema applier — in one dependency. Nothing is *enabled* merely
by being present; see [CONFIGURATION.md](CONFIGURATION.md).

Prefer plain `JdbcTemplate`? Use `aipersimmon-ddd-starter-jdbc`. Want to pick modules one at a time?
That is fully supported — see [CHOOSING-MODULES.md](CHOOSING-MODULES.md).

### 2. An aggregate

An aggregate root is the transactional consistency unit. Extend `AbstractAggregateRoot` and it gets
identity equality, a domain-event buffer, and an optimistic-lock version.

```java
public class Order extends AbstractAggregateRoot<OrderId> {

  private static final Transitions<OrderStatus> RULES =
      Transitions.<OrderStatus>of().allow(OrderStatus.PLACED, OrderStatus.CONFIRMED);

  private final OrderId id;
  private OrderStatus status;

  public static Order place(OrderId id) {
    Order order = new Order(id, OrderStatus.PLACED);
    order.registerEvent(new OrderPlaced(id.value()));
    return order;
  }

  /** Rebuilt from a row: hand back the stored version so the next write can check against it. */
  public static Order reconstitute(OrderId id, OrderStatus status, long version) {
    Order order = new Order(id, status);
    order.restoreVersion(version);
    return order;
  }

  public void confirm() {
    // A state table, not scattered ifs: an illegal transition throws instead of being written.
    RULES.check(status, OrderStatus.CONFIRMED);
    status = OrderStatus.CONFIRMED;
    registerEvent(new OrderConfirmed(id.value()));
  }

  @Override
  public OrderId id() {
    return id;
  }
}
```

### 3. A repository

Extend the base and you get the version predicate, the affected-rows check, and event draining. Skip
the base and each of those becomes yours to remember, every time.

```java
@Repository
public class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderDo> implements Orders {

  public MyBatisOrders(OrderMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
  }

  @Override
  public void save(Order order) {
    saveAggregate(order); // versioned write + publishAndClear, in the caller's transaction
  }

  @Override
  protected OrderDo toRow(Order order) {
    OrderDo row = new OrderDo();
    row.setId(order.id().value());
    row.setStatus(order.status().name());
    return row;
  }
}
```

The row carries `@Version private Long version` and implements `VersionedRow`; the table has a
`version BIGINT NOT NULL DEFAULT 1` column. A concurrent write matching no row raises
`OptimisticLockingFailureException`, which the command bus translates to
`ConcurrencyConflictException` and the web layer renders as **409 Conflict**. That chain is what makes
the aggregate a real consistency boundary rather than a suggestion.

### 4. A command and its handler

```java
public record ConfirmOrder(String orderId) implements Command<Void> {}

@Component
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

  private final Orders orders;

  @Override
  public Void handle(ConfirmOrder command, CommandContext context) {
    Order order =
        orders
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));
    order.confirm();
    orders.save(order);
    return null;
  }
}
```

`OrderingErrorCode` is your own `ErrorCode` enum: a stable machine-readable code per failure, which
is what lets the web layer map it to a problem type without the domain knowing about HTTP.

The bus wraps every dispatch in logging → retry-on-conflict (opt-in) → validation → prechecks →
concurrency translation → transaction, mints
the message id, seeds the tenant, and drains domain events inside the transaction. You do not put
`@Transactional` on the handler.

### 5. An endpoint

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

  private final CommandBus commandBus;

  @PostMapping("/{id}/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirm(@PathVariable String id) {
    commandBus.send(new ConfirmOrder(id));
  }
}
```

A `DomainException` or `ApplicationException` thrown anywhere below becomes an RFC 9457 problem
document with the right status — no `@ExceptionHandler` of your own.

### 6. The schema

Your tables are yours. The framework's tables (outbox, inbox, process manager, operation log, web
stores) ship as Flyway migrations; list only the components you actually use:

```yaml
aipersimmon:
  ddd:
    flyway:
      components: [outbox, inbox]
```

Listing nothing creates nothing.

---

## What you get, and what it costs to operate

| You add | You get | It costs |
| --- | --- | --- |
| `-starter` | command/query buses, in-process events, UUIDv7 ids, RFC 9457 errors | nothing to operate |
| `-starter-mybatis-plus` / `-starter-jdbc` | the above, plus versioned repositories, outbox, inbox, process manager, operation log, tenancy, Flyway | tables, and background pollers once configured |
| `-starter-messaging-kafka` | `@Externalized` events cross a broker, exactly-once in effect | a broker, a topic, and the inbox |

Cross-service messaging is built **on** the outbox, not instead of it: a storage bundle is a
prerequisite. Adding the Kafka bundle without one fails startup rather than letting `@Externalized`
events be published in process and silently never leave the JVM.

## Conventions worth knowing before you write code

- **The aggregate is the transaction.** One command, one aggregate, one version-checked write. When a
  flow spans aggregates or contexts, reach for the process manager — not for a bigger transaction.
- **Domain events are in-process facts; integration events cross a boundary.** Different types in
  different modules, on purpose. A domain event never leaves the JVM.
- **Who mints an id matters.** `send`/`publish` mint a fresh identity; `sendAs`/`publishAs` carry an
  existing one, which is what keeps a replay idempotent. Business code uses the first pair.
- **`Invariant` throws, `Specification` answers.** Using one where the other belongs is how
  exceptions become control flow, or how an illegal state gets written.
- **A degraded capability says so.** A missing id generator, `@Externalized` events with no durable
  outbox, or an enabled protection running on an in-memory store each produce a startup failure or a
  WARN naming the remedy — never silence.

## Extending

Every bean is `@ConditionalOnMissingBean`: declare your own and the framework steps aside. The seams
designed to be replaced are `IdGenerator`, `OutboxDispatcher`, `FailureClassifier`,
`IdempotencyStore` / `ReplayGuard` / `RateLimiter`, `TenantResolver`, `ProcessPayloadCodec` /
`ProcessStateCodec`, `OperationLogSink`, and the observability SPIs.

`aipersimmon-ddd-archunit` ships the layering rules as tests you can run against your own code.

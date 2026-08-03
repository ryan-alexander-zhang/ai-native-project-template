# S6 — When a request cannot continue without another service's answer

An order needs a risk decision before it can exist. The decision is not something that can arrive later,
so this is the one integration style the rest of the samples avoid: a synchronous call, in the request's
own thread, with the caller's availability now multiplied by the callee's.

Companion document: `docs/analysis/analysis-00030-samples-synchronous-call-between-services.md`.

## Run it

```bash
mvn -pl s06-synchronous-call-between-services/risk-service      -am verify   # 11 tests
mvn -pl s06-synchronous-call-between-services/ordering-service  -am verify   # 10 tests
```

The risk service needs **no Docker at all** — it owns nothing to contain. The ordering service needs
PostgreSQL and skips without Docker; the callee it talks to is a real HTTP server on a real socket, in
the test JVM.

## When synchronous, and when not

The question the catalogue puts first, and the only one here that cannot be answered by a test:

| | this sample (risk) | S4 (inventory) |
| --- | --- | --- |
| Can the caller proceed without the answer? | No — an unassessed order must not exist | Yes — the order stands, stock follows |
| Is the answer a *judgement* or a *reservation*? | A judgement, valid at a point in time | A reservation, which must be held |
| What does unavailability cost? | The request fails | Nothing; the outbox waits |

The useful test is the middle row. A synchronous call can obtain a **judgement** ("is this acceptable?")
and can never obtain a **held reservation**, because the answer stops being true the moment it is given
and nothing on the other side is holding anything for you. If what you need is held, you need the other
side to hold it — a saga or a distributed transaction (S9/S10), not a question.

And the first row is the honest cost: this caller cannot place an order while the risk service is down.
That is not a bug to be engineered away, it is what "must have the answer" means. The engineering choice
is only whether to fail closed or open, and that is a business decision — written down as a `catch` block
that exists or does not, never as an accident.

## Where the call goes: a precheck, not the handler

The framework runs `CommandPrecheck`s between validation (order 100) and the transaction interceptor
(order 200). Everything about the caller's shape follows from those two numbers, and a test asserts them
directly by recording `isActualTransactionActive()` in both places:

| | during the precheck | inside the handler |
| --- | --- | --- |
| transaction active | **false** | **true** |

Two dividends. **No database connection is held while the network waits** — the same call on the
handler's first line would pin a connection for the whole round trip, and one slow dependency would then
drain the pool, which is how a service dies of something that was merely slow elsewhere. And **a refusal
costs nothing to undo**: the transaction never opened, so there is no partial write and no compensation
to write. The test asserts the order table is untouched *and* that the handler never ran.

What a precheck cannot give you: atomicity. It is advisory by construction — the world may change
between the check and the commit — so a risk answer is a point-in-time judgement and not an invariant
this service enforces. Which is exactly why it fits risk and would not fit a credit hold.

## The port, the adapter, and the domain that must not call

| | where | why |
| --- | --- | --- |
| `RiskAssessments` port | `application` | "What does another context think" is a collaboration between use cases, not part of how an aggregate exists |
| `HttpRiskAssessments` adapter | `infrastructure` | The only class that knows there is HTTP involved |
| the domain | nowhere near it | An ArchUnit rule in this module says so |

The aggregate has no `riskApproved` flag and no reference to the service. A domain model that makes a
network call has tied its invariants to somebody else's uptime and can no longer be tested without a
stub — which is the practical reason the rule survives contact with a deadline.

## How the callee's contract arrives

Hand-written records in the caller's `infrastructure`, carrying the three fields it actually uses. The
three options, because this is the real engineering fork:

| | cost |
| --- | --- |
| **Shared `api` jar** | The callee's release becomes the caller's recompile; every field added for somebody else arrives here, and "which contract version does this caller hold" becomes a Maven question |
| **OpenAPI codegen** | Removes hand-copying and drift, at the cost of the spec being published, correct and versioned — and the generated DTOs still live in your codebase, so the coupling has the same shape, only automated. Right when the contract is large |
| **Hand-written** (here) | Two records; the caller's view is exactly the subset it uses, so an unrelated upstream change is a no-op |

What is not an option is passing the callee's DTO inward past the adapter.

## Two kinds of "no", and they must not look alike

The callee returns **200 with `approved: false`** for a rejection and reserves 4xx for a malformed
request. Both are tested. If a rejection arrived as a 422, the caller could not tell a business refusal
from its own broken payload — one is a message for the customer, the other is a page for an engineer.

The caller then translates, and nothing above the adapter sees a status code:

| what the callee did | what this service raises | what the client sees |
| --- | --- | --- |
| 200, `approved: false` | `RiskRejectedException` (`ordering.risk-rejected`) | **422**, domain-rule family type |
| timeout, connect failure, 5xx | `RiskUnavailableException` (`ordering.risk-unavailable`) | **503**, its own problem type |
| 4xx problem document | `RiskUnavailableException` | **503** — a 4xx means *this* service sent something wrong, and the answer to a defect here is not to tell the customer their order was refused |
| 200 with a body this caller cannot read | `RiskUnavailableException` | **503** — a missing `approved` is not `false`, which is why the client DTO uses a boxed `Boolean` |

The 503 needs one library seam worth knowing about: `ErrorCategory` has no "a dependency is down" member,
so `UNEXPECTED`'s family default would render 500 — wrong twice over, since it blames this service and
tells the client not to retry. A `ProblemCatalog` bean overrides that one code to 503. The rejection code
gets no override on purpose: it rides the domain-rule family type like every other refusal and is
distinguished by its `code`.

## Timeouts and retries

Two timeouts are configured because **Spring's default request factory has none**: a callee that accepts
a connection and then goes quiet would otherwise hold a request thread indefinitely.

The retry is **one attempt, and only because the call is a query** — asking twice is asking. A test
counts the requests, so "retried once" is an assertion; removing the retry turns exactly those two
assertions red. A state-changing remote call could not be retried this way without an idempotency key
from the callee, and getting that wrong is how one request becomes two credit holds.

Not implemented, and named rather than half-built: circuit breakers, bulkheads, shared retry budgets.
Those belong to a resilience library or the mesh.

## The database-less bundle

The risk service takes `aipersimmon-ddd-starter` and nothing else from the framework — no persistence,
outbox, inbox or flyway module, since each of those exists to make a database do something. What is left
is worth having: the buses, the interceptor chain, id generation, and RFC 9457 problem responses.

One property comes with it and it is **not optional**: with no `DataSource` there is no transaction
manager, so the starter's "one command, one transaction" guarantee cannot hold — and rather than let it
evaporate silently, **startup fails** with a `FailureAnalyzer` naming the two ways out. A deployment that
means it sets `aipersimmon.ddd.cqrs.transaction.required: false` and gets a WARN on every boot.
`TransactionlessDeclarationTest` asserts both halves, because "we picked the right bundle for a stateless
service" is exactly the kind of claim that turns out to have been silently wrong.

## Three things this cost

All three are this sample's, not the library's. The first is a library guard doing its job on code that
deserved it; the other two are test-writing mistakes.

1. **A precheck registered as a lambda is refused at startup** — prechecks are indexed by their type
   parameter and a lambda erases it (`PrecheckCommandInterceptor:93-109`). Same strictness, and same
   reason, as S21's upcaster registry: a bean indexed under the interface would silently never run. An
   anonymous class with a diamond is fine — checked, not assumed:
   `ResolvableType.forInstance(...).as(CommandPrecheck.class).getGeneric(0)` resolves to `PlaceOrder` for
   a named class, an anonymous one with `<>`, and an anonymous one with the argument written out, and only
   to `Command` for a lambda.
2. **`SpringApplicationBuilder.properties(...)` loses to `application.yaml`** — it contributes *default*
   properties. The first version of the startup test therefore "proved" the app boots happily without the
   declaration, when the yaml's own value had simply won. Command-line arguments outrank the file.
3. **The stub's default executor is single-threaded — one bug with two symptoms, and the second one framed
   an innocent suspect.** A delayed response blocks every following request from entering the handler. So
   the slow test's retry never reached the request counter ("the client did not retry" — it had), *and*
   that test's 2-second sleep was still holding the executor when the next test ran, whose request then
   timed out too: the risk precheck threw, a refusing precheck short-circuits the ones after it, and the
   transaction-state probe never ran and reported `null`.

   That second symptom was first written up here as "a precheck must be a named class", which was
   invention: two changes had gone in together and the tests went green, and the wrong one got the credit.
   **When two changes land together, isolate before writing down which one worked.** A stub's concurrency
   has to match what the client can have in flight, and a shared stub carries state between tests as
   surely as a database does.

## Not demonstrated here

| | |
| --- | --- |
| Both services booted against each other | The caller is tested against a real HTTP stub in its own JVM; the callee against its own API. An end-to-end harness is the same gap S4 and S21 have. |
| Circuit breaking, bulkheads, retry budgets | Named above; they belong to a resilience library or the mesh. |
| A command-shaped remote call (asking the callee to change state) | The asymmetry is argued in the companion; making it safe needs the callee's idempotency key, which is S2's subject from the other side. |
| A held reservation across services | S9/S10. |
| mTLS, propagating the caller's identity | Out of scope for every sample so far. |

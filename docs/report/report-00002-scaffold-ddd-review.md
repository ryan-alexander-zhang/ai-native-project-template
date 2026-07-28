---
id: report-00002-scaffold-ddd-review
type: report
role: main
status: active
parent: plan-00015-scaffold-depth-and-evaluability
---

对 `aipersimmon-ddd-scaffold/multi-module` 的一次整体评审：它同时是**组件库的用法样例**和**别人复制去起新项目的 DDD 参考实现**，所以从两个身份分别审——DDD 建模是否走样，以及作为脚手架是否够用、可生产、易上手。

评审基线：分支 `lang/java/ddd`，`29e9661`。方法：全量精读 `multi-module` 下全部源文件（3 个上下文 × 5 层 + `ordering-process-mybatis-plus` + `start`）、22 个 `start` 集成测试、全部 domain 单测、3 个 Flyway 迁移、`application.yml`、`compose.yaml`，并回溯库侧 `AiPersimmonDddRules` 与 `aipersimmon-ddd-test-support`。

判定基准：以经典 DDD（Evans / Vernon）为标尺，同时核对 `docs/decision` 的 19 条既有决策；两者冲突处单列。

---

## 0.5 修复进度（2026-07-28 更新）

**全部 High 已修完。29 个 issue 中 22 已 resolved，7 open，全为 Medium/Low。**
每个 resolved issue 的「验证结果」一节记录了实际改法、与原修复方案的差异、以及负向对照的实测输出——
**接手时读那一节，不要只读「修复」一节**，两者有出入的地方不少。

九个提交（分支 `lang/java/ddd`，基线 `259199a`）：

| Commit | 内容 |
|---|---|
| `1c4a682` | issue-00073 索引 + issue-00091 复合外键（`V4`） |
| `d026050` | issue-00074 profile 拆分 + issue-00072 种子移出生产迁移；连带发现并修 issue-00096 |
| `c67c387` | issue-00094 库存泄漏（评审 B2，最严重一条）+ issue-00076 + issue-00093 |
| `ce85174` | issue-00071 信用额度强一致 + issue-00086 Customer 收窄为「信用」聚合（`V5`） |
| `27c640c` | issue-00068 三个 deadline（STOCK / PAYMENT / STOCK_RELEASE） |
| `97c5a6c` | issue-00069 支付幂等：端口改 `find`+`record`，`payment_operations` 表（`V6`） |
| `383e7cc` | issue-00077 Money 溢出保护 + issue-00083 总额物化（`V7`） |
| `66cad29` | issue-00090 子行不再无条件重写 + issue-00084 库存批量读端口 |
| `238d0de` | issue-00070 `READY_FOR_FULFILMENT` 真实落库 + 自助取消/预留竞态 |

**尚未修（7 条）**：

| Issue | 等级 | 一句话 |
|---|---|---|
| [[issue-00075-a-zero-amount-order-can-be-placed-but-not-paid]] | Medium | 0 元订单跨上下文契约不一致 |
| [[issue-00095-a-partial-reactor-build-silently-tests-stale-siblings]] | Medium | `-am` 陷阱；核心警告已在 README，剩护栏 |
| [[issue-00097-the-payment-operation-log-has-no-cleanup]] | Low | **修 00069 时新引入**：幂等日志无保留期 |
| [[issue-00080-problem-title-key-has-no-message-bundle]] | Low | RFC 9457 的 title 是 message key，无资源包 |
| [[issue-00082-domain-surface-no-use-case-can-reach]] | Low | 不可达领域能力；`PaymentOperations.find` 那一处已消解 |
| [[issue-00085-ordering-carries-sku-as-a-bare-string]] | Low | 同一概念两种建模精度 |
| [[issue-00087-a-raw-control-character-is-the-codec-separator]] | Low | codec 分隔符是裸控制字符，字段无转义 |
| [[issue-00092-each-test-context-starts-its-own-container-pair]] | Low | 容器数量未文档化 |

**两条跨 issue 的经验，接手前值得知道**：

1. **`mvn` 命令必须带 `-am`**。不带会静默用 `~/.m2` 里的陈旧兄弟构件，
   症状指向完全错误的方向（本轮踩了三次：`POST /orders` 返回 405、找不到刚加的类）。
   见 [[issue-00095-a-partial-reactor-build-silently-tests-stale-siblings]]。
2. **每条修复都做了负向对照**（临时破坏修复、确认测试真的会红），
   而且**多次发现 issue 里提议的断言会假绿**——最典型的是 issue-00077：
   `assertThrows(DomainException.class, ...)` 在溢出回绕成负数时会被"金额不能为负"满足。
   接手时请沿用这个做法：新写的测试先破坏实现验证它会红。

---

## 0. 总体判断

**战术建模层面，这是同类参考实现里的第一梯队。** 真正做对而多数项目做不对的地方：

- **证据承载式取消理由**。`CancellationReason` 是 sealed interface，`PaymentDeclinedAfterStockReleased` 在构造期就要求 `StockReleaseRef`——补偿没跑过，这个理由**类型上就构造不出来**。这比"枚举 + 注释约定"强一个数量级。
- **`Specification` 回答 / `Invariant` 拒绝**。`CancellableByCustomer.BEFORE_FULFILMENT` 被 `OrderLifecyclePolicy` 复用，一条规则一处声明，"能不能取消"的建议与"拒绝的理由"不会漂。
- **`OrderLine` 包私有**。聚合内部实体在编译期不可达，只能经根进入。
- **`ProcessDefinition` 是纯函数，且按 `(step, input)` 二维分派**。乱序/重复事实走 `ignore` 而不抛异常——这是 at-least-once 下唯一正确的写法，`react` 的注释还逐条列出了类型单维分派会犯的三个错。
- **领域层零框架**，且贯彻到了极端处：`CancelOrder` 宁可手写 codec，也不肯在 `ordering-domain` 的 sealed 类型上加 `@JsonTypeInfo`。
- **测试可信度高**。真 PostgreSQL + 真 Kafka；`ConcurrentApprovalTest` 用仓储装饰器在 barrier 上**编排**竞态而不是"跑两次碰运气"；`MybatisPlusInterceptorCompositionTest` 断言的是"两个 InnerInterceptor 都还在、顺序对"这种会静默消失的东西。

**核心诊断：作为「组件用法演示」优秀；作为「可复制的生产骨架」还差三步。**

| | 现状 |
|---|---|
| 战术 DDD 建模 | ★★★★★ |
| 战略 DDD / 上下文映射 | ★★★★☆ |
| 分层与依赖治理 | ★★★★★ |
| 测试可信度 | ★★★★★ |
| 能力演示完整性 | ★★★☆☆ |
| 生产可用性 | ★★☆☆☆ |
| 易用性（上手即跑） | ★★☆☆☆ |

三步差距：**安全域完全缺席**、**若干失败路径没有闭环**、**README 的 quickstart 跑不通**。

一个贯穿性的观察：**本轮 Blocker/Major 里有 4 条，类注释或 README 声称已经处理了，而实现没有兑现**（B2、M1、M2、M3）。对一个把自己作为正确性范本的项目，注释与实现的落差比缺陷本身更值得修——读者会照着注释理解，而不是照着代码。

---

## 1. Blocker（已落盘）

| | 问题 | 落盘位置 |
|---|---|---|
| B1 | **README quickstart 100% 跑不通**：端口写 8080（实际 8090，且 8080 是 compose 里的 kafka-ui），且缺 `X-Tenant-Id`（`missing-policy: REJECT`），补上后还必须是 `__root__` 才有种子数据 | [[issue-00093-the-readme-quickstart-cannot-succeed]] |
| B2 | **`ReserveStockHandler` 的失败路径永久泄漏库存**：`catch (DomainException)` 吞掉异常，事务不回滚，已落库的前几行扣减照常提交，却没有 `Reservation` 可供归还——与类注释的声明直接矛盾 | [[issue-00094-a-swallowed-domain-exception-leaks-stock-permanently]] |
| B3 | **无认证无授权**：取消他人订单、批准他人审核、枚举他人订单、`/ops/dead-letters/**` 裸奔且被排除在租户校验之外 | [[design-00013-actor-identity-and-authorization]] |

B3 是从"演示"到"骨架"最大的一道坎，且它会让其它所有优点打折。它走 design 而非 issue，
因为要定的是 seam 而不是补丁：actor 从哪进入调用链、它与 tenant 已有的传播骨架什么关系、
哪些判断留在领域。B1、B2 是有明确复现与修法的缺陷，仍按 issue 落。

---

## 2. Major（已落 issue）

| Issue | 问题 |
|---|---|
| [[issue-00068-stock-waits-have-no-deadline-and-can-park-forever]] | "payment 是唯一可能永不作答的外部步骤"这个论断是错的；`AWAITING_STOCK` / `AWAITING_STOCK_RELEASE` 同样外部作答却无 deadline |
| [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]] | 演示的幂等模式有回滚洞（认领在事务外），且重投递应重发已记录结果而非静默返回——`find()` 定义了却从未调用 |
| [[issue-00070-ready-for-fulfilment-is-never-persisted]] | 下单事务里把 READY 与 IN_PROGRESS 压成一个状态，`READY_FOR_FULFILMENT` 从不落库，客户自助取消在主流程不可达 |
| [[issue-00071-credit-limit-is-checked-but-not-enforced]] | 跨聚合不变量在应用服务里裸检查，无并发保护也无补偿——既不强一致也不最终一致 |
| [[issue-00072-demo-seed-data-ships-in-a-production-migration]] | 演示种子数据写在 `V1` 生产迁移里，会在每个环境执行 |
| [[issue-00073-no-index-supports-the-cursor-paged-list]] | 三个迁移零 `CREATE INDEX`，而游标分页被当作性能特性宣传 |
| [[issue-00074-one-config-file-with-development-values-only]] | 单一 `application.yml` 全是开发值，无 profile、无外部化数据源、无探针分组、无优雅停机 |
| [[issue-00075-a-zero-amount-order-can-be-placed-but-not-paid]] | `@PositiveOrZero` vs `@Positive` 的跨上下文契约不一致，0 元订单下单成功但两分钟后被超时取消 |
| [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]] | inventory 靠 ordering 的 `OrderHasDistinctSkus` 才不出事，违反限界上下文自治 |

## 3. Minor（已落 issue）

[[issue-00077-money-arithmetic-has-no-overflow-guard]] ·
[[issue-00078-six-places-still-describe-the-repositories-as-in-memory]] ·
[[issue-00079-review-decision-id-bypasses-the-id-generator]] ·
[[issue-00080-problem-title-key-has-no-message-bundle]] ·
[[issue-00081-openapi-examples-name-a-status-that-does-not-exist]] ·
[[issue-00082-domain-surface-no-use-case-can-reach]] ·
[[issue-00083-the-order-total-rule-is-restated-in-sql]] ·
[[issue-00084-stock-availability-check-is-one-query-per-sku]] ·
[[issue-00085-ordering-carries-sku-as-a-bare-string]] ·
[[issue-00086-customer-is-an-aggregate-nothing-writes]] ·
[[issue-00087-a-raw-control-character-is-the-codec-separator]] ·
[[issue-00088-dependency-and-image-versions-escape-the-boms]] ·
[[issue-00089-the-generated-project-links-a-document-it-does-not-have]] ·
[[issue-00090-order-lines-are-rewritten-on-every-save]] ·
[[issue-00091-the-order-lines-foreign-key-omits-the-tenant]] ·
[[issue-00092-each-test-context-starts-its-own-container-pair]]

**评审之后新增**（修复过程中暴露，非本次评审所见）：

- [[issue-00096-the-quickstart-curl-names-a-tenant-the-edge-rejects]] ——
  README 快速开始的 curl 必然 400：种子只在 `__root__`，而该值是客户端不可命名的保留租户。
  由 [[issue-00072-demo-seed-data-ships-in-a-production-migration]] 第 4 条实施时撞出，已修。
- [[issue-00097-the-payment-operation-log-has-no-cleanup]] ——
  `payment_operations` 无保留期。由 [[issue-00069-payment-idempotency-claim-is-outside-the-transaction]]
  的修复引入：把 `ConcurrentHashMap` 换成表时，"进程重启即清空"这条**隐含**的保留策略消失了，
  而它从未被写下来过，所以替换时没有东西提醒需要一个替代品。**未修。**

---

## 4. 能力覆盖度

**已演示且演示得好（22 项）**：CQRS 双总线 · 聚合与显式状态机 · 领域事件（聚合产出、应用层订阅）· 集成事件 + 事务性 outbox + Kafka + inbox 幂等 · CloudEvents · ACL（出站 gateway + 入站 listener）· Open Host Service · 持久化 process manager + 有序补偿 + deadline · 乐观锁 → 409 · HTTP 幂等键（含租户隔离）· 游标分页读模型 · 死信查看与重放 · RFC 9457 · 多租户端到端 · 操作日志 · UUIDv7 · 可观测性三路 OTLP · OpenAPI · Bean Validation 双层 · ArchUnit 24+4 条 · PIT 90% · Testcontainers 真中间件。

**缺口**，按对真实项目的重要性排序：

| 缺口 | 说明 |
|---|---|
| 认证 / 授权 / 权限 | 完全缺席（B3）。真实项目第一天就要面对 |
| 事件版本演进 | 全部 `version = 1`，无 upcasting / 双写兼容示例。`OrderFulfilmentCodecs` 的注释详细讨论了 upcasting，却一个例子都没有 |
| 配置与 profile 管理 | [[issue-00074-one-config-file-with-development-values-only]] |
| i18n | [[issue-00080-problem-title-key-has-no-message-bundle]] |
| 业务定时任务 | 框架 worker 有，业务调度示例没有（ShedLock 已在依赖里） |
| 缓存 | 无。读模型 / 聚合 / 跨上下文查询三处都是天然位点 |
| 限流 / 重放保护 | README 称"与幂等只差计数对象"，但两者失败语义不同，理由偏弱 |
| 批量 / 导入 / 导出 | 无 |
| 软删除与数据保留 | 无 |
| 文件 / 附件 | 无 |
| feature flag / 灰度 | 无 |
| 读写分离 / 分库分表 | 无（多租户只演示 pool，未演示 silo/bridge） |

README 的「Not demonstrated here, on purpose」只列了 5 项，**上面 12 项里只有 2 项被承认过**。未被声明的 gap 是本次评审的主要产出之一。

---

## 5. 经典 DDD ↔ 本仓库决策：冲突点

| 议题 | 经典 DDD | 本实现 | 判定 |
|---|---|---|---|
| 一事务一聚合 | Vernon 规则 #4 | `ReserveStockHandler` 刻意跨 N 个 `Stock` + 1 个 `Reservation` | **权衡成立**（每 SKU 一行是自然争用边界，合并成大聚合会串行化无关库存），但 B2 说明兑现不完整 |
| 跨聚合不变量 | 同聚合强一致，或最终一致 + 补偿 | 应用服务裸检查，无并发保护无补偿 | **缺陷**，见 [[issue-00071-credit-limit-is-checked-but-not-enforced]] |
| 聚合根不引用其他聚合根实例 | 只持 identity | `Order` 只持 `CustomerId` | **符合** |
| 领域层零框架 | — | ArchUnit 强制，且贯彻到 codec 层 | **符合，超出常见水准** |
| 仓储只面向聚合根 | — | `Orders`（写）/ `OrderQueries`（读）分离 | **符合** |
| Specification vs Invariant | 可组合谓词 vs 断言 | 共享同一条规则声明 | **超出正典水准**，但主流程走不到，见 [[issue-00070-ready-for-fulfilment-is-never-persisted]] |
| 上下文自治 | BC 自保不变量 | inventory 依赖 ordering 的不变量 | **缺陷**，见 [[issue-00076-inventory-relies-on-an-upstream-invariant-to-protect-itself]] |
| 聚合的定义 | 有生命周期、会被修改 | `Customer` 从不被修改 | **建模偏差**，见 [[issue-00086-customer-is-an-aggregate-nothing-writes]] |

**与 `docs/decision` 的一致性：19 条全部被遵守。** 逐条核对了 00005（package-per-aggregate）、00008（subscriber 在 application 层）、00013（`CommandContext` 因果链）、00014（CloudEvents）、00015（gateway ACL）、00016（`context.cause().messageId()` 作为证据 id）、00017（操作日志）、00018（多租户）、00019（UUIDv7，仅 [[issue-00079-review-decision-id-bypasses-the-id-generator]] 一处例外）。**未发现决策与实现的冲突。**

---

## 关联

- [[plan-00015-scaffold-depth-and-evaluability]]（本轮评审的基线：scaffold 深度与可评估性）
- [[report-00001-ddd-framework-review]]（同类评审，对象是组件库本身）
- [[decision-00018-multi-tenancy-boundaries]]、[[decision-00019-time-ordered-uuidv7-identifiers]]、[[design-00011-aggregate-persistence-contract]]

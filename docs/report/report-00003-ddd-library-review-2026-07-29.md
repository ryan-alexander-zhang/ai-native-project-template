---
id: report-00003-ddd-library-review-2026-07-29
type: report
role: main
status: active
parent: report-00001-ddd-framework-review
---

对 `aipersimmon-ddd/` 库树（47 个模块，约 2.8 万行 main 代码，725 个 Java 文件）的第二次全量评审：
是否忠实于 DDD、是否生产可用、有哪些 Bug 与性能问题、哪些地方该重构或重写。

评审基线：分支 `lang/java/ddd`，`7951ef6`。方法：七路并行精读，每路覆盖一个模块族的全部 main + test
源码与 SQL 资源，逐条要求 `file:line` 证据与可复现的失败场景，禁止臆测；核心结论由主控回读代码抽查核实。

判定基准：经典 DDD（Evans / Vernon）+ `docs/decision` 既有 19 条决策。前提：尚未上线，**不计成本可以重写，
不考虑兼容性**——所以本报告的建议优先"结构上做对"，而不是"最小改动绕过"。

---

## 0. 总体判断

**DDD 符合度：高。** 战术层比多数自研 DDD 框架忠实：纯净层（core / cqrs / integration / application /
tenancy / observability / web / 各组件契约）经字节码规则验证零 Spring 依赖；聚合按标识相等且 `equals`
被 `final` 锁死；事件在聚合内部记录；元数据不进命令载荷；ORM 注解只落在 DO 行对象上，`restoreVersion`
为 protected 只对重建工厂开放；`Invariant`（断言式、强制 ErrorCode）与 `Specification`（决策式、可组合）
的切分有水准。

**生产可用性：尚不可用。** 核心正确性扎实，**短板在运维层与降级路径**。缺陷高度收敛于两个系统性主题，
不是散落的技术债：

1. **租户体系全线 fail-open。** `orElse(Tenants.ROOT.value())` 散布 14 处 / 9 个模块，
   `MissingTenantException` 是死代码，`MissingTenantPolicy` 只在 HTTP 边缘被咨询。丢绑定即静默读写
   `__root__` 哨兵桶。→ 已修，见 `issue-00099`。
2. **静默降级。** 反复出现"文档承诺 A、代码行为 B、且无任何日志"：无事务管理器时命令裸跑；消费方自带
   `MybatisPlusInterceptor` 时乐观锁整体消失；Flyway 组件默认全建表却在 bundle pom 里写着"打包不等于建表"。
   同一份代码对缺失 `IdGenerator` 是启动即失败的——风险姿态自相矛盾。

**核实为扎实、不要动的部分：** outbox 的原子写入 / 诚实的 at-least-once / 按聚合有序的回退 `NOT EXISTS`
方案（比多数生产级 outbox 更讲究）/ 死信迁移原子且存储故障能自愈 / `issue-00044` 修复验证无误 /
两后端 SQL 逐条比对无漂移；乐观锁协议（版本 0 → insert，0 行 → 拒绝且不发事件、不进版本）；
UUIDv7 六个铸造点一致且 fail-loud；process-manager 的精确一次推进、确定性 effect/deadline id、
deadline 代际栅栏、租约 fencing；operation-log 的 outcome×completion 模型与跨库幂等收敛。

---

## 1. Critical（5 条，均已回读代码核实）

| # | 位置 | 缺陷 | 状态 |
|---|---|---|---|
| C1 | `IdempotencyFilter` + `AipersimmonDddWebAutoConfiguration:173` | 幂等过滤器注册在 `HIGHEST_PRECEDENCE+40`（Spring Security 链 order −100 **之前**），存储键仅 `tenant + key`，不含认证主体/方法/路径。攻击者带受害者的 `Idempotency-Key` 与租户值发任意请求即可取回其已存响应，全程绕过安全链 | **已修** `issue-00101` |
| C2 | `IdempotencyFilter:83-95` | "只执行一次"不成立：时序为 `find`(miss) → `doFilter`(副作用提交) → `saveIfAbsent`，无执行前 claim。两个并发首次请求都执行；`saveIfAbsent` 只决定谁的**响应**被存 → 重复扣款 | **已修** `issue-00101` |
| C3 | `FailedOperationLogInterceptor:89` | 以"存在活动事务"判定自己是嵌套子命令而让根去记录；但若最外层 `commandBus.send()` 本身在 `@Transactional` 内被调用（消费方极常见），根本不存在记录者 → 整条流程的失败审计被静默跳过，无日志、无 `failureRecordLost` 指标 | **已修** `issue-00102` |
| C4 | `ProcessOperations:80-118,180-200` | redrive 先提交 resume 事务，再在**任何事务之外**重放 parked 输入且无持久化待重放标记。注释声称 `handle()` never throws，实际可抛四类异常。崩溃/抛异常落在提交之后 → 实例停在 RUNNING、parked 输入永久丢失（broker 已 ack），全树无任何扫描去捡 | 待修 |
| C5 | `DefaultProcessRuntime:426-540` + `ProcessOutcomeWriter` | 终态决策从不取消未决 deadline（含每次 start 都武装的 max-lifetime backstop）；claim 查询要求 `lifecycle IN ('RUNNING','COMPENSATING')` 故永不可领取，`cancelPending` 唯一调用点是操作员 cancel → 健康检查永久 DEGRADED、年龄单调增长、轮询扫描无界膨胀 | 待修 |

---

## 2. Major（按模块族）

**Outbox / 事件管道**
- 崩溃即最长 60 分钟全线停摆：ShedLock 只在任务正常结束时清 `lock_until`，`kill -9`/OOM/掉节点后无人接手，
  所有集成事件投递静默停摆且不报错。60 分钟默认值是被最坏批次预算（100 × 30s）顶上去的——
  一个旋钮同时耦合"最大轮询时长"与"崩溃恢复时间"，是 relay 最大的架构弱点。
- 零 Micrometer 指标：积压深度、最老未发送年龄这两个最经典的 outbox 告警必须手写 SQL 才能得到。
- 吞吐上限约 100 msg/s：每轮只抽一批、批间固定等 1s、每条 send 同步 `get(timeout)`（放弃 producer 批处理与流水线）、
  每条成功一次独立 `MARK_SENT`。1 小时故障积压 180 万行要排约 5 小时。
- **静默丢失**：outbox 行不存目的地，路由在派发时按当前注解决定。写入时带 `@Externalized`、派发时路由表已无该
  `(type, version)`（版本升级保留 v1 类但漏注解、滚动发布期间）→ `topicFor()` miss → 投给进程内腿 → 标记已发送。
  永不到 broker、无报错、无死信、无消费延迟可观测；两个启动守卫都看不见这条路径。
- DLT 固定源分区号（`MessagingKafkaAutoConfiguration:286-290`）：`.DLT` 分区数少于源主题时发布失败 →
  `DefaultErrorHandler` 重新 seek 重试整轮 → 毒消息永远出不去、分区无限停滞，与 DLT 目的完全相反。

**Process Manager**
- `withRetry` 在加入外层事务时失效——而这正是 javadoc 宣传的组合模式：首次尝试的 `StaleProcessRevisionException`
  把共享物理事务标记 rollback-only（PostgreSQL 上直接 aborted），重试循环永不可能成功，只靠传输层重投兜底。
- 并发首次 start 漏 `DuplicateKeyException` 映射（`JdbcProcessInstanceStore:92`）：输家以 Spring 原生异常冒出，
  `withRetry` 不接，文档承诺的 duplicate/fold/reject 语义被绕过（transition store 做了映射，instance store 没做）。
- Effect claim 队头 `NOT EXISTS` 随实例历史线性变慢（索引 `(instance_id, seq)` 不含 status）；全局 `ORDER BY e.seq`
  系统性饿死长寿实例；四张表**全无保留/清理策略**——成本无界增长。
- SKIP LOCKED 的 deadline claim SQL 在两个真实数据库上零测试覆盖；MariaDB 被识别为 mysql 走 SKIP LOCKED，
  但 10.6 之前不支持 → 每轮语法错误、effect 永不投递且不 fail-fast。

**持久化（写路径核心是全框架最扎实的部分，以下是其降级路径）**
- `MybatisPlusAggregateRepository:82` 用 `updateById`，MP 默认 `NOT_NULL` 策略把 null 列从 SET 剔除 →
  领域方法清空可选字段：版本检查通过、事件发布、库里仍是旧值，重建时僵尸字段复活，全程无错。
  这是该基类本该中和的头号 MP 陷阱，javadoc 亦无提醒。
- 消费方自带 `MybatisPlusInterceptor` 时乐观锁拦截器整体消失、退化 last-writer-wins，且**启动日志恰好印在
  会 back-off 的那个 bean 里**；`InnerInterceptorCompositionTest` 已把这个失败模式测出来却仍然发货。
  三行可修：MP 的 locker 会把 `newVersion` 写回实体，`updateById` 成功后断言 `version == expected + 1`。
- Flyway 组件默认 apply-all 与两个 bundle pom 宣称的"打包不等于建表"直接矛盾；`CONFIGURATION.md` 亦写着
  "Empty creates nothing"。**同一行为三份文档两种说法**，必须先定方向再改文档。

**CQRS / 核心**
- handler 构造注入 `CommandBus` 会启动循环依赖（`AipersimmonDddCqrsAutoConfiguration:67-73` 在工厂方法内
  `handlers.stream().toList()` 提前实例化全部 handler），而这正是框架文档推荐的子命令派发写法。
- `domainEvents()` javadoc 承诺快照，实际返回活视图 → 同步监听器回写同一聚合时 `ConcurrentModificationException`。
- 无 `PlatformTransactionManager` 时 `UnitOfWork` 与事务拦截器静默 back-off，starter 的招牌保证蒸发且无警告。
- 命令失败只 DEBUG 记录，默认 INFO 生产配置下失败命令一行日志都没有。

**Web / 可观测性**
- 错误响应被存下并在整个 TTL 内重放（5xx 冻结 24 小时，客户端重试永远拿到失败）。
- `JdbcRateLimiter` 窗口边界竞态抛 `EmptyResultDataAccessException` → 间歇 500。
- web-store JDBC 两张表无清理路径且无 `expires_at` 索引 → 无界增长（一次性 key 的"改写时顺带清理"永不触发）。
- 兜底 500 处理器不记日志（`AipersimmonDddWebExceptionHandler:198-202`）→ 生产 NPE 无栈可查。
- `ReplayProtectionFilter` 默认作用于全部请求且认证前无上限缓冲请求体（探针被 401 打死 + 内存 DoS 面）。

**架构层**
- outbox 家族没有 engine 层：relay/retry/backoff/死信/租约轮询——全框架最关键的并发代码——在 jdbc 与
  mybatis-plus 各维护一份（`OutboxRelayScheduler` 除包名外字节相同）。process-manager 与 operation-log
  已用 engine-over-store-ports 解过两次，outbox 早于该重构且从未跟上。
- BOM 继承 parent → Maven 解析被 import BOM 的**有效模型**，Spring Boot 3.5.10 等全部 pin 泄漏给消费方
  并压过对方选的 Boot 版本。BOM 自己特意重声明 springdoc/OTel"以便对齐"，说明作者以为其余不传播——但会传播。
- `process-manager-engine` 4567 行零直接测试，且在所有覆盖率门禁之外（JaCoCo/PIT 只覆盖 6 个纯净层模块）。
  门禁分布与风险分布正好相反。
- core 内部违反自己的"一个概念一个名字"：`core.annotation.DomainEvent` vs `core.event.DomainEvent`、
  `annotation.AggregateRoot` vs `model.AggregateRoot`——同名双词汇表，混用即需全限定名，ArchUnit 追两套。
- 6 个模块编译依赖 `aipersimmon-ddd-id-spring-boot-starter` 却零 import，只为把 JUG 拖上 classpath；
  47 个 pom 对 2.8 万行过度碎片化（中位约 300 行，`-inbox` 只有 1 个 42 行接口）。
- `ModuleNamingChecks` 用正则解析 pom，分不清 `<dependencyManagement>` 与注释掉的 XML；
  `aipersimmon-ddd-outbox` 这个"契约模块"已因此偷带 jackson + slf4j 还带着实现类。

---

## 3. 建议的修复顺序

**发布阻断（按序）**
1. 租户改为 fail-closed（`issue-00099`，**已完成**）
2. 幂等 SPI 重写为 claim 状态机 + 移到安全链之后 + 键含主体与请求指纹 + 5xx 不落库（`issue-00101`，**已完成**）
3. operation-log 失败路径：真 `REQUIRES_NEW` 记录 + 修两个默认分类器（`issue-00102`，**已完成**）
4. process-manager C4/C5，并把 `withRetry` 改 `REQUIRES_NEW`、给 instance store 补异常映射
5. 把静默降级统统改成响亮失败（无 TM 拒绝启动、两个仓储基类加活动事务断言、MP 乐观锁版本回写断言、
   `OutboxWriter` 事务断言）——框架已为 `IdGenerator` 立了 fail-loud 先例，照它办

**紧接其后**
6. 抽出 `aipersimmon-ddd-outbox-engine`，让 inbox/outbox 与 process-manager 形状一致
7. relay 换行级 claim（`FOR UPDATE SKIP LOCKED`；H2 用 per-row `claimed_until`），解开 60 分钟停摆与预算耦合
8. 写入时持久化目的地到 outbox 行：路由消失从静默本地投递变成死信
9. 加 metrics SPI（挨着现有 tracer SPI，接缝已在）
10. 流水线化 Kafka 腿（按 subject 有序发出、按序等 future、首个失败 fail-fast；语义不变，吞吐 10–50 倍）
11. BOM 去 parent，只管理 `com.aipersimmon.ddd:*` 与刻意再导出的坐标
12. 测试门禁反转：两个 engine 补内存 store 单测 + JaCoCo；加 reactor 级 ArchUnit 用字节码强制契约模块无框架依赖
13. core 二选一删掉一套建筑块词汇表；47 模块收敛到约 20

---

## 4. 遗留观察（不在上述条目内）

- ~~`OutboxCleanupTest` 间歇失败，成因未查明。~~ **已查明并修复**：`purge()` 同时挂
  `@Scheduled(fixedDelay)` 与 `@SchedulerLock`，而 `@Scheduled` 的首跑在上下文就绪后**立即**发生并持有锁；
  测试通过注入的代理直调同一方法时若撞上，ShedLock 拿不到锁便静默返回——断言于是作用在一次没发生的 purge 上。
  见 `issue-00100`。同类风险：任何 `@Scheduled + @SchedulerLock` 的方法被测试直调都有这个陷阱。

## 关联

- 父：[[report-00001-ddd-framework-review]]（第一次库评审）
- 兄弟：[[report-00002-scaffold-ddd-review]]（脚手架评审，30 个 issue 已全部 resolved）
- 子：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]、
  [[issue-00100-a-scheduled-purge-steals-the-lock-from-its-own-test]]、
  [[issue-00101-idempotency-records-instead-of-claiming]]、
  [[issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction]]

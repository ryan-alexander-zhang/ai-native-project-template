---
id: decision-00017-operation-log-component-boundaries
type: decision
role: main
status: active
parent: analysis-00013-operation-log-component
---

# 通用操作日志组件：定位、模块、事务与安全边界

固化 `aipersimmon-ddd` **通用操作日志（Operation Log）组件**在编码前必须团队背书的决策：它是什么、拆几个模块、
两种入口如何归一、事务与幂等语义、以及模板/隐私/租户的安全边界。承接预研 [[analysis-00013-operation-log-component]]
（逐调用链审计了 `mzt-biz-log` / `log-record` 两套参考实现）与结构设计 [[design-00008-operation-log-component]]
（本 ADR 只固化"决策与取舍"，机制细节以 design-00008 为准，不在此重复）。

本 ADR 受既有决策约束：[[decision-00010-command-handler-reuse-and-cross-aggregate-placement]]、
[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]、[[decision-00012-no-ambient-per-command-state]]、
[[decision-00013-command-context-and-causation-propagation]]、[[decision-00014-cloudevents-integration-event-contract]]、
[[decision-00016-durable-runtime-staged-message-identity]]、[[design-00003-exception-model]]、[[design-00005-observability-and-distributed-tracing]]。

## 结论先行

> **clean-slate 实现，不 fork / 复制 `mzt-biz-log` / `log-record`；只保留其"需求模型"，弃用其运行时内核
> （AOP、ambient state、开放 SpEL、内存异步、单一 success 布尔）。组件是 application 横切能力，不是 domain building
> block，也不是 Audit Log。持久化模型采用 `outcome`（业务结果）× `completion`（事务完成态）两个正交且非空的枚举。
> 拆五个模块（core / engine / cqrs-spring / jdbc / mybatis-plus），framework-free 内核 CQRS-free，DDL 与 `OperationLogs`
> pipeline 落在 engine，两个存储后端二选一并共享 DDL。注解只叠加在 application `Command` 上、由 `CommandInterceptor`
> 解释，不做通用 method AOP。成功日志与业务同 datasource 同事务（fail-closed）；异常日志走独立事务且绝不改写原业务
> 异常。模板用受限 property-path 语法而非完整 SpEL；隐私默认拒绝、可真正删除；幂等键含 `outcome+completion`。
> v1 只同步本地 append，不复用 outbox、不引入进程内异步队列、不承诺合规审计。**

## Context

analysis-00013 的三路交叉审计确认：两套参考实现的**需求**（业务可读文案、before/after、稳定 operationCode、
operator/租户、可替换存储）成立，但其**实现内核**——Spring method AOP 单入口、`InheritableThreadLocal`/TTL 变量池、
`StandardEvaluationContext + BeanFactoryResolver` 开放 SpEL、默认内存异步 + 紧循环 retry、以及"成功/失败压成一个布尔"
——恰好逐条撞上本库 accepted decisions 与生产可用性底线。

design-00008 已给出结构设计。本 ADR 把 analysis-00013 §12 / design-00008 §14 列出的 13 条待拍板项固化为决策，
使其不再由布尔开关或实现细节隐式决定。用户已确认 **MyBatis-Plus 后端必做**，故模块决策按"两个后端"定稿。

## Decision

### A. 定位与模型

1. **（D1）Operation Log ≠ Audit Log。** 组件只做面向业务阅读者的操作日志，默认 append-only，但**不承诺**
   WORM / 签名 / hash-chain / 法定留存 / 防篡改。合规审计另立 Audit Log profile + 独立 ADR，不靠给普通表贴
   `append-only` 标签冒充。
2. **（D1）`outcome` 与 `completion` 正交、均非空。** `outcome ∈ {SUCCEEDED, REJECTED, FAILED}` 表业务结果；
   `completion ∈ {COMMITTED, ROLLED_BACK, NOT_STARTED, UNKNOWN}` 表事务完成态。禁止用单一 success 布尔或
   `async`/`joinTransaction` 开关表达（见命题一）。

### B. 模块与依赖

3. **（D2）拆五个模块**：`aipersimmon-ddd-operation-log`（framework-free、CQRS-free 纯契约）、
   `-operation-log-engine`（storage-agnostic：`OperationLogs` 默认实现 + 装配 + 三方言 DDL）、
   `-operation-log-cqrs-spring`（CommandInterceptor 捕获 + 受限模板 + resolver）、`-operation-log-jdbc`、
   `-operation-log-mybatis-plus`。依赖单向如 design-00008 §二；消费方 domain 模块不得依赖 operation-log（ArchUnit 强制）。
4. **（D2）DDL 与 `OperationLogs` pipeline 落在 engine**，两个存储后端只实现 `OperationLogSink`/`OperationLogReader`
   端口、共享同一份 DDL、**二选一**。direct-API（batch/scheduler/CLI）消费者只依赖 `core + engine + 一个后端`，
   不被迫拉入 CQRS（见命题二）。

### C. 入口与生命周期

5. **（D3）注解只标 application `Command`、由 `CommandInterceptor` 解释；不做通用 method AOP。** `@OperationLog`
   是叠加元数据，不替代 `Command<R>`（对齐 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]）；
   捕获点唯一为 `CommandBus` 拦截链（见命题三）。将来若确有非 CQRS 的注解需求，另加独立 `-method-spring` adapter，
   且必须复用同一 `OperationLogs`。
6. **（D4）统一生命周期 `prepare / complete / failed`**（签名见 design-00008 §5.3）；显式 Definition 与注解编译产物
   走同一 `OperationLogs` pipeline，不绕过直写 sink。
7. **（D4/D12）启动期 fail-fast**：一个 input type 出现"注解+Definition 双绑、重复 Definition、泛型不可判定"即启动失败；
   v1 每次调用只产生**一条** entry，不支持 repeatable annotation；多记录需先定义 group identity + 原子 `appendAll` +
   部分失败语义，另开 ADR。
8. **（D4/D11）无 ambient 状态**（对齐 [[decision-00012-no-ambient-per-command-state]]）：成功与失败两路各持
   invocation-local 不可变对象，只共享可重复读取的 compiled metadata，禁 ThreadLocal / TTL / global map / 静态 util /
   SpringContext 查 bean。

### D. 事务、失败、幂等

9. **（D5）两个 interceptor + 可插拔 `FailureClassifier`。** `CompletedOperationLogInterceptor` 在事务内、handler 外层
   写 `SUCCEEDED`/committed `REJECTED`；`FailedOperationLogInterceptor` **外于并发翻译 + validation + transaction**
   （order < 50），独立事务写 `REJECTED`/`FAILED`。异常分类由 core 的 `FailureClassifier` SPI 决定，默认对齐
   [[design-00003-exception-model]]（预期业务/校验/授权异常 → `REJECTED`；`ConcurrencyConflictException` → `FAILED` +
   `CONCURRENCY`；其余技术异常 → `FAILED`），消费方可覆盖。order 数值（design-00008 §6.1 候选 25 / 250）由集成测试锁定，
   但**包裹关系是硬约束**：Failed 必须外于并发翻译（否则只能看到未翻译的 `OptimisticLockingFailureException`），Completed 必须内于事务。
10. **（D6）v1 要求成功日志与业务写模型同 `DataSource` / 同 `PlatformTransactionManager`。** 这是成功日志取得原子性的
    前提，也是 v1 唯一模式；多事务管理器时用配置显式指定。异库 / 跨库的 durable staging 不在 v1，另立 ADR（见命题四）。
11. **（D10）成功路径 fail-closed、失败路径不改写原异常。** 成功 append 加入当前业务事务、提交前写入，genuine append 失败使
    业务回滚（重投的**重复键**用方言原生 `ON CONFLICT DO NOTHING` 收敛而不 abort 事务，见命题五）；失败 append 由
    **root**（最外层）`Failed` interceptor 在整条链回滚完成、无活动事务后以独立事务写入，随后**重新抛出原业务异常**；
    嵌套子链的 `Failed` 检测到活动外层事务时不开新事务、上交 root，避免 `REQUIRES_NEW`-while-parent-open 的连接池耗尽。
    失败日志自身写失败只吞并打 metric + alert。`OperationLogSink` 对事务无感，传播语义只在 interceptor 层表达。
12. **（D9）时间有序 `recordId` + result-kind 感知的 `idempotencyKey`。** `recordId` 用 UUIDv7/ULID（经注入的
    `Supplier<String>`）；CQRS 路径 `idempotencyKey = SHA-256_hex(messageId|operationCode|outcome|completion)`（定宽 64、
    抗碰撞、含分隔符），DB 唯一键 `(tenant_id, source, idempotency_key)`；duplicate 返回既有 `recordId`。至少一次投递下
    （[[decision-00016-durable-runtime-staged-message-identity]] 保证 `messageId` 跨重投稳定）"先 FAILED 后 SUCCEEDED"产生
    两行、各自幂等（见命题五）。direct-API 若可重试须由调用者传稳定 key。
13. **（D13）v1 只同步本地 append。** 不复用 Integration Event outbox（[[decision-00014-cloudevents-integration-event-contract]]）、
    不引入固定线程池/内存队列/紧循环 retry；诚实声明失败日志的崩溃窗口（design-00008 §8.3）。异步 exporter / Audit
    profile 各自另立 ADR（见命题六）。

### E. 安全与租户

14. **（D7）受限 property-path 模板，非完整 SpEL。** 根对象限 design-00008 §6.3，纯函数白名单 `{mask, truncate,
    defaultValue}`，禁 bean/`T(...)`/构造器/任意方法/反射/IO；启动期全量编译校验 + 有界 cache + 尺寸预算。若成本迫使
    复用 SpEL，最低边界为只读 `SimpleEvaluationContext`（见命题七）。
15. **（D8）隐私默认拒绝、failure 清洗、可真正删除。** 默认无字段可记录，逐项 allowlist；secret/token/凭据/生物信息
    永不入库；PII 掩码；入库前去 CR/LF；failure 只存 `code/category/safeSummary`（无 stack/SQL/原始异常正文）。retention/purge
    端口 + 每租户 TTL、隐私删除/crypto-shred（必须真正移除旧值并留治理审计）**在 P3 定义**；本 ADR 只确立"必须可真正删除、
    不得永不删除"的原则，不承诺 v1 交付该端口。
16. **（D8/D11）多租户全链路强制 tenant。** 开启后写入、唯一键与**所有读取**都带可信 tenant（`OperationLogCriteria`
    强制要求），非多租户模式规范化为 `GLOBAL`（DB 不用 NULL）。
17. **（D11）actor/tenant 用无状态 resolver、可信来源，不扩展 `CommandContext`。** resolver 无状态/无 IO/无副作用；
    仅当存在自动捕获入口且 resolver 缺失时启动失败；可信来源为安全上下文或显式 invocation scope，**绝不**从 command
    payload 取；batch/scheduler 显式用 `SYSTEM`/`SERVICE` actor。不给 `CommandContext` 加 actor/tenant/metadata map
    （对齐 [[decision-00013-command-context-and-causation-propagation]]）；跨异步边界传播"原始操作者"另立 ADR。

## Rationale

### 命题一 —— 单一 success 布尔表达不了真实生产语义

`mzt-biz-log`/`log-record` 用一个布尔或"是否抛异常"表示成败，无法区分两个都真实发生的场景：**正常返回但业务拒绝
并已提交**（`REJECTED+COMMITTED`，如"库存不足，订单置为拒绝并落库"）与**回滚后仍要留痕**（`FAILED+ROLLED_BACK`）。
把二维压成一维，是这类组件最常见的语义债，直接导致运营看板把"用户/业务拒绝"与"系统故障"混为一谈。DDD 上 outcome
属 ubiquitous language 的业务概念、completion 属技术事务事实，二者正交、不能互相推导，故建成两个非空枚举列。

### 命题二 —— MyBatis-Plus 确定要做 → engine seam 一次到位，避免返工

若按 analysis-00013 §4.3 的"MVP 只放 `-jdbc`、DDL 暂居其中、后再提升"，等 MyBatis-Plus 落地时必须把 DDL 与
`OperationLogs` 装配上移、改两个后端的依赖——一次性返工。既然用户已确认第二后端必做，直接对齐 process-manager
的 **engine seam + 双后端代码现状**（模块 `process-manager` / `-engine` / `-jdbc` / `-mybatis-plus`，DDL 居 `-engine`；
见记忆 `process-manager-engine`——注意 [[design-00004-durable-process-manager-runtime]] 原文描述的是更早的三模块形态，
engine 是其后重构，分层思路可参但模块清单以代码为准）即最省。engine 还让 direct-API 消费者无需为记录一条日志而拉入整个
CQRS 捕获栈——这是 DDD"按稳定性/依赖方向分层"的直接体现。

### 命题三 —— `CommandInterceptor` 比通用 method AOP 更适合作 DDD 捕获点

`CommandBus` 是本库唯一命令入口，interceptor 链天然规避 Spring proxy 的 self-invocation、public/final/private、代理
类型、advisor 顺序、异步返回完成态、重复记录等边界（Spring 官方明确 proxy self-invocation 绕过 advice）——美团方案
正踩在这些坑上。注解落在 application `Command` 也让"哪一层负责记录"无歧义（不在 controller/domain/repository），
符合 [[decision-00010-command-handler-reuse-and-cross-aggregate-placement]] 与 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]。

### 命题四 —— 同库同事务是"成功日志原子性"的唯一简单可靠解

绝大多数服务只有一个主关系库。让 sink 复用业务的 `DataSource`/事务管理器，成功日志便与业务变更同提交/同回滚，
天然杜绝"有日志无业务"或"有业务无日志"。异库必然引入最终一致 + 补偿 + durable staging（outbox/CDC），是更重的能力，
不应作为 v1 默认承担；把它显式推到后续 ADR，避免用一个 boolean 假装原子性。

### 命题五 —— 幂等键必须含 outcome+completion，否则会吞掉真实的结果演进

durable 命令运行时是至少一次投递（[[decision-00016-durable-runtime-staged-message-identity]]）。若幂等键只含
`messageId+operationCode`，同一命令"第一次 FAILED、重投后 SUCCEEDED"的第二条会被误判为重复而丢弃——真实的状态演进
被吞。把 `outcome+completion` 纳入 append identity：完全相同的 result kind 才收敛，"失败后成功"保留两条真实记录。
`recordId` 选时间有序 id（UUIDv7/ULID）而非随机 UUID，是为保住主键/索引的 B-tree 插入局部性，避免高写入下的页分裂——
这是大厂 DB 主键的通行实践。

**收敛机制在成功路径必须方言原生（评审阻断项修正）**：成功路径的 append 在业务事务内。PostgreSQL 在事务内任一语句
报错即把整个事务置为 aborted，随后提交被转成回滚——因此"catch `DuplicateKeyException`"在成功路径会让**重投时业务变更被
静默丢失**并误写 `FAILED`。故成功路径的重复键必须用不 abort 事务的 `INSERT ... ON CONFLICT DO NOTHING`（PostgreSQL）/
`INSERT ... ON DUPLICATE KEY`（MySQL）或 `SAVEPOINT`；catch-异常收敛只在失败路径那条隔离的独立事务里安全（详见 design-00008 §7.3）。

### 命题六 —— "内存异步 + 紧循环 retry"不是 durable，是静默丢数据

固定线程池、内存队列、紧循环重试在崩溃/背压下会静默丢日志，却对外表现得"像异步、像可靠"。生产上宁可同步本地 append
（简单、可测、随业务事务原子），也不要假可靠。真正的异步导出需要容量/背压/持久化状态/指数退避+jitter/lease-fencing/
优雅停机/DLT/backlog age/幂等消费一整套——这是单独能力，另立 ADR，不塞进 MVP。

### 命题七 —— 开放 SpEL 是副作用面与（模板外置后的）RCE 面

参考实现的 `StandardEvaluationContext + BeanFactoryResolver` 允许 bean 查找、`T(...)`、构造器与任意方法调用：即便模板
来自编译期注解，getter 副作用、隐藏 IO、敏感数据泄漏已是现实风险；一旦模板外置到 DB/配置中心即升级为动态代码执行面
（OWASP）。受限 property-path 语法 + 启动期编译 + 尺寸预算，既满足"业务可读文案"的真实需求，又把攻击面与"慢模板/超大
payload 拖垮写路径"的运维风险一并关掉。

## 备选方案与否决

| 备选 | 否决理由 |
|---|---|
| 直接用 `mzt-biz-log` | Spring 2.x 耦合、ambient context、开放 SpEL、事务/tenant/幂等语义不足 |
| 直接用 `log-record` | core 不纯、显式与注解入口不对等、默认内存异步、无 durable/原子保证、开放 SpEL |
| fork 后大改 | 需推翻运行模型与模块边界，改造量接近重写，还背兼容负担 |
| 通用 method AOP 作主入口 | 与既有 `CommandBus` 重复，proxy/事务边界复杂（命题三）；仅作未来独立 adapter |
| 单一 success 布尔 / `joinTransaction` 开关 | 表达不了 `REJECTED+COMMITTED` 与 `FAILED+ROLLED_BACK`（命题一） |
| 完整 SpEL 模板 | 副作用/注入/RCE 面（命题七） |
| 默认进程内异步队列 | 非 durable，崩溃/背压下静默丢数据（命题六） |
| 默认跨 datasource | 放弃成功日志原子性，被迫提前引入 staging（命题四） |
| MVP 只放 `-jdbc`、DDL 暂居其中 | MyBatis-Plus 已确定要做，会一次性返工（命题二） |

## Consequences

- 组件以 clean-slate 落地五模块；`mzt-biz-log`/`log-record` 仅作需求样本与反例库，不进代码基座。
- 消费方能以注解、Definition、direct-API 三种入口归一到同一模型与 sink；domain 层不依赖组件（ArchUnit 新增规则）。
- 成功日志获得同事务原子性（fail-closed）；异常日志走独立事务并保留崩溃窗口的诚实声明；幂等在至少一次投递下正确演进。
- 明确牺牲：v1 不提供合规审计、异步导出、跨库原子、多记录、通用 method AOP——各自留到有真实需求时的后续 ADR。
- 后续待办：本 ADR 已产出 [[spec-00001-operation-log-component]] 与 [[plan-00010-operation-log-implementation]]（均 `draft`）；
  待本 ADR review→active 后开工。落地含：把 design-00008 §13 验收矩阵转为可执行测试（"后端 × 方言"参数化）；在
  `CONTEXT.md` 增补 Operation Log / Audit Log / Operation Outcome / Transaction Completion 术语；新增相关 ArchUnit 规则。

## Sources

内部：

- [[analysis-00013-operation-log-component]]（两套参考实现的逐调用链审计与需求追踪）
- [[design-00008-operation-log-component]]（本 ADR 的机制承载文档：模型、端口、DDL、interceptor、时序）
- [[spec-00001-operation-log-component]]（feature spec）、[[plan-00010-operation-log-implementation]]（落地计划）
- [[decision-00010-command-handler-reuse-and-cross-aggregate-placement]]、[[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]、
  [[decision-00012-no-ambient-per-command-state]]、[[decision-00013-command-context-and-causation-propagation]]、
  [[decision-00014-cloudevents-integration-event-contract]]、[[decision-00016-durable-runtime-staged-message-identity]]
- [[design-00003-exception-model]]（`FailureClassifier` 默认分类对齐）、[[design-00004-durable-process-manager-runtime]]
  （core/engine/后端 四段式模板）、[[design-00005-observability-and-distributed-tracing]]
- 代码：`aipersimmon-ddd/aipersimmon-ddd-cqrs/.../CommandInterceptor.java`、`.../CommandContext.java`；
  `aipersimmon-ddd/aipersimmon-ddd-cqrs-spring/.../TransactionCommandInterceptor.java`（order 语义）

外部：

- 美团：如何优雅地记录操作日志 —— https://tech.meituan.com/2021/09/16/operational-logbook.html
- Spring Framework，proxying / self-invocation —— https://docs.spring.io/spring-framework/reference/core/aop/proxying.html
- Spring Framework，SpEL EvaluationContext —— https://docs.spring.io/spring-framework/reference/core/expressions/evaluation.html
- OWASP Logging Cheat Sheet —— https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html

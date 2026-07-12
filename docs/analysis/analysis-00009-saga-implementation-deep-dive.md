---
id: analysis-00009-saga-implementation-deep-dive
type: analysis
role: main
status: active
parent: analysis-00007-saga-process-manager
---

# Saga 已落地实现深度剖析:原理、数据模型、能力边界与 Seata 对照

承接 [[analysis-00007-saga-process-manager]](该文回答"要不要、以什么形态提供 saga、何时升级引擎"的**设计取舍**)。
本文转向**已经落地的 `aipersimmon-ddd-saga` / `-saga-spring` 实现本身**:它到底怎么工作、怎么持久化、
实现了哪些能力、缺了哪些,以及与工业级状态机型实现(Seata SAGA)的数据模型差距。

配套阅读:[[analysis-00006-ddd-building-blocks-library]](构件库纯/脏分离方法论)、
[[analysis-00001-domain-event-publishing]](事件发布 / outbox,saga 可靠推进的底座)、
[[analysis-00002-domain-vs-integration-events]](跨 BC 用集成事件)。

## 结论先行

> **当前是一个轻量、务实的「编排式(orchestration)」Saga 内核:状态机守卫 + 关联路由 + 乐观锁 +
> 补偿状态 + 两档可靠超时(内存 / JDBC 轮询)。它把补偿逻辑、事件路由、命令派发有意留给应用手写;
> 把重试策略、死信、多实例租约、整体超时、审计、监控、DSL、durable 引擎留白。**

- **是编排式,不是协同式**:核心是 `@ProcessManager` 中央协调者 + 集中式持久化状态机。协同式在本库无需专门构件,用事件基础设施即可。
- **持久化 = 纯 JDBC + 关系表**,不用 JPA。saga 实例一行(`aipersimmon_saga`),持久化超时一行(`aipersimmon_deadline`)。
- **step 没有独立列**:业务步骤被序列化进 `data` blob——这是与 Seata 三表模型最本质的差距(§八、§九)。
- **升级路径未变**:契约引擎无关,日后可换 durable-execution 引擎而不重写流程(承接 00007 的判词)。

---

## 一、架构:两模块,端口—适配器落到模块级

| 模块 | 角色 | 依赖 | 框架 |
| --- | --- | --- | --- |
| `aipersimmon-ddd-saga` | **纯契约**:标记 + 状态基类 + 端口 | `-core` | 无(framework-free) |
| `aipersimmon-ddd-saga-spring` | **适配器**:JDBC 持久化 + 超时调度 + 自动装配 | `-saga` | Spring / JDBC |

设计原则与全库一致(见 [[analysis-00006-ddd-building-blocks-library]]):业务只依赖抽象契约,
底层实现(内存定时器 / 数据库轮询 / durable 引擎)可替换而不动 saga 逻辑。

## 二、核心契约(`aipersimmon-ddd-saga`)

| 类型 | 职责 |
| --- | --- |
| `@ProcessManager` | 标记中央协调者(编排式,与"无协调者的 choreography"对立) |
| `SagaState` | 持久化状态基类:携带 `correlationId` + `SagaStatus` + `version`,**守卫合法转换** |
| `SagaStatus` | 闭合枚举:`RUNNING / COMPENSATING / COMPLETED / ABORTED`;后两者 terminal |
| `SagaStore<S>` | 持久化端口:`find(correlationId)` / `save(saga)`,要求乐观锁 |
| `Deadline` | record:`(correlationId, name, fireAt)`——一个命名超时 |
| `DeadlineScheduler` | 端口:`schedule` / `cancel` |
| `DeadlineHandler` | 回调:`onDeadline(deadline)`,超时到期时被调用 |

**状态转换被锁在基类**——子类不能直接改 `status`,只能调受保护方法,非法转换抛 `IllegalStateException`:

```
startCompensation()  // requireStatus(RUNNING) → COMPENSATING
complete()           // requireStatus(RUNNING) → COMPLETED
abort()              // 非终止态 → ABORTED
isActive()           // = !status.isTerminal()
```

## 三、编排式 vs 协同式:本库怎么实现

| | 协同式 Choreography(默认) | 编排式 Orchestration(saga 模块) |
| --- | --- | --- |
| 本库如何实现 | **无专门 saga 构件**;用 `events-spring`(进程内)或 `outbox-jdbc + messaging-kafka`(跨服务)发布/订阅事件,各上下文自行 react | `saga` + `saga-spring`:`@ProcessManager` + `SagaState` + `SagaStore` + `DeadlineScheduler` |
| 有无协调者 | 无 | 有(中央状态机) |
| 何时用 | 步骤少、耦合低 | 步骤/分支/超时多到需要显式状态机 |

证据:`@ProcessManager` 注释直接把两者对立;`SagaState` 是集中持久化状态;示例 `OrderFulfilment` 在一处
react 所有输入(下单 / 库存预留 / 超时);`DeadlineScheduler` 提供协调者主动等待+超时补偿。**saga 模块 = 编排式。**

## 四、Saga 与状态机的区别(常见混淆)

不是二选一——**saga 内部就用状态机实现**,但两词强调不同:

- **状态机**:通用建模模型(状态、事件、转换),不关心业务/跨边界/补偿。
- **Saga**:分布式长事务模式,靠"每步本地事务 + 失败补偿"保证最终一致。

Saga 独有、纯状态机没有的三样:**补偿(`COMPENSATING`)**、**跨边界的本地事务序列**、**长等待与超时(`Deadline`)**。
本库 `SagaState`+`SagaStatus` 是小状态机骨架,再叠加补偿 + 关联路由 + 超时 = saga。

## 五、幂等性:为什么 saga 绕不开(以支付为例)

saga 建立在"至少投递一次 + 可能重试"之上,**支付这类不可逆副作用步骤必须幂等**,否则重复扣款。
要区分**两层幂等**:

1. **接收端(saga 消费事件)**:重复收到不能重复推进 → 靠去重表 `Inbox`(`inbox-jdbc` 模块)。
2. **发送端(支付动作本身)**:重试不能重复扣款 → 靠**业务幂等键**(通常用 `correlationId`/订单号)透传给支付网关。

本库提供的三道防线(接收端):

- `Inbox.alreadyProcessed(key)`:唯一键去重,**须与处理同事务**(失败回滚以便重投重试)。
- `JdbcSagaStore` 乐观锁:并发/重复事件只有一个 `WHERE version=?` 命中,另一个抛 `OptimisticLockingFailureException`。
- 生命周期守卫 + `filter(isActive)`:已终止 saga 的迟到事件 / deadline 是 no-op。

**关键提醒**:`saga-spring` 只依赖 `saga`,**不自动引入 `inbox-jdbc`**;需要接收端去重的应用要**显式**加该依赖。
补偿动作(退款、释放库存)同样可能重试,**补偿也必须幂等**。

## 六、持久化与数据模型

纯 JDBC,两张表,DDL 随包但**不自动执行**(需纳入 Flyway/Liquibase)。

### `aipersimmon_saga`(saga 实例,`JdbcSagaStore` 读写)

```sql
CREATE TABLE aipersimmon_saga (
    correlation_id VARCHAR(128) NOT NULL,   -- 主键;事件按它路由
    status         VARCHAR(32)  NOT NULL,   -- SagaStatus.name()
    version        BIGINT       NOT NULL,   -- 乐观锁
    data           CLOB,                    -- 流程数据,子类自行序列化(不透明)
    CONSTRAINT pk_aipersimmon_saga PRIMARY KEY (correlation_id)
);
```

`save` 用 `version` 区分插入/更新:`version==0` → INSERT(起始版本 1);否则版本校验 UPDATE,
`updated==0` 抛乐观锁异常。子类只实现 `mapRow()` / `serializeData()`(显式映射,非反射)。

### `aipersimmon_deadline`(持久化超时,`JdbcDeadlineScheduler` 读写,仅 `store=jdbc` 时)

```sql
CREATE TABLE aipersimmon_deadline (
    correlation_id VARCHAR(128) NOT NULL,
    name           VARCHAR(128) NOT NULL,   -- 一个 saga 可有多个命名超时
    fire_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_aipersimmon_deadline PRIMARY KEY (correlation_id, name)
);
```

两表按 `correlation_id` 逻辑关联(**无外键**),一个 saga 可有 0..N 个待触发超时(`name` 区分)。

### 两档超时调度(`aipersimmon.ddd.saga.deadline.store`)

| 实现 | 机制 | 跨重启 | 多实例 | 语义 |
| --- | --- | --- | --- | --- |
| `SchedulingDeadlineScheduler`(默认 `in-process`) | `TaskScheduler` 堆内定时器 | ❌ 丢失 | ❌ | 单实例、分钟级 |
| `JdbcDeadlineScheduler`(`jdbc`) | 建表 + `@Scheduled` 轮询"存行→到期派发→成功删行,失败留行重试" | ✅ | ✅ | **at-least-once**,靠 saga 幂等兜底 |

两者的 handler 都**懒解析**(`Supplier<DeadlineHandler>`),使 process manager 既能 arm deadline 又能当 handler,避免构造期循环依赖。

## 七、能力边界:实现了什么 / 没实现什么

### ✅ 已实现

编排式 Process Manager、持久化状态 + 关联路由、受守卫生命周期状态机、JDBC 持久化 + 乐观锁并发控制、
补偿**状态**、命名超时、两档可靠超时(含 JDBC at-least-once + 失败重试)、自动装配 + 示例 DDL。

### ❌ 未实现

**A. 有意留给应用(设计取舍)**

1. **补偿编排本身**——`COMPENSATING` 只是状态;不自动记录已完成步骤、不自动逆序补偿,撤销逻辑手写。
2. **事件路由/关联自动化**——手动接监听器 + `store.find(correlationId)`;无 `@SagaEventHandler` 式注解分发。
3. **命令派发/回复关联**——无内建 reply channel。
4. **状态序列化**——`serializeData`/`mapRow` 手写,无内建 JSON 序列化器。

**B. 框架层面缺失**

5. 步骤 DSL / 声明式流程;6. 重试退避 / 最大次数(JDBC 轮询只是无限重试);7. 死信 / 毒丸阈值;
8. 多实例租约(JDBC 轮询无 `SKIP LOCKED`,跨实例会重复触发);9. 整体 Saga 超时 / TTL;
10. 历史 / 审计 / 事件溯源(单行可变记录);11. 监控 / 运维面;12. durable 引擎接入;
13. 子 saga / 组合;14. `data` schema 演进由应用负责。

## 八、`SagaStatus` 是写死的 + status 与 step 两轴

`SagaStatus` 是**闭合枚举**(4 态),转换也写死在 `SagaState` 守卫方法里。这是**有意**的——它表达
**框架层面的生命周期**,不是业务步骤。要分清两个正交的轴:

| 轴 | 谁管 | 存哪 | 取值 |
| --- | --- | --- | --- |
| 生命周期 `status` | 框架 | `status` 列 | 写死 4 态 |
| 业务 `step`(进度) | 你的子类 | **`data` 列里** | 你自定义,任意多步 |

多步 saga 应在子类里放自己的 `enum Step` 字段并序列化进 `data`(可选用 `core` 的 `Transitions` 守卫 step 迁移)。
**不要动 `SagaStatus`**——那属于改库本身,会牵动 `isTerminal()` / 转换守卫 / `status` 列取值。

## 九、核心批评:step 埋进 blob 的代价(与 Seata 对照)

**质疑**:saga 本质是多步的,但 `aipersimmon_saga` 只有一个 `status` + 不透明 `data`,没有 step 列,怎么跟业务关联?

**回答**:step 确实存了,只是塞进 `data` blob,不是一等列。业务关联 = `correlation_id`(路由)+ `data`(进度)。
一次事件往返:`find(orderId)` → `mapRow` 反序列化重建 step → saga 按 step 反应并推进 → `serializeData` 写回。

**代价(质疑成立)**:① 不能 SQL 按 step 查询/统计("多少订单卡在 AWAITING_PAYMENT");② DB 层无可观测性;
③ 框架不校验 step 转换(只守卫 4 个宏观 status);④ `JdbcSagaStore` 的 SQL **把四列写死**,想加 `step` 列
必须整个覆写 `save`/`find`——这是真实局限。

### Seata SAGA 的解法:把每一步落成一行(三表模型)

Seata 用**状态机引擎 + 三张表**,step 是一等公民、完全可查询:

```
seata_state_machine_def   状态图定义(一张图一行,content 存 JSON DSL,recover_strategy=compensate|retry)
        │ machine_id
seata_state_machine_inst  状态机实例(跑一次一行;status 与 compensation_status 两个独立轴;business_key+tenant_id 唯一)
        │ machine_inst_id
seata_state_inst          状态实例(每执行一个节点一行:service_name/method、input/output_params、
                          status=SU/FA/UN/SK/RU;补偿行用 state_id_compensated_for 指回正向步,重试行用 state_id_retried_for)
```

- **持久化+执行**:`DbStateMachineConfig` 从 `resources`(`statelang/*.json`)加载 DSL 写入 def;
  `startWithBusinessKey(...)` 建 inst 行;引擎逐节点驱动,每个 `ServiceTask` 调服务后**插一行 state_inst**。
- **恢复**:`compensate()` 读 state_inst 找已成功步骤,按 DSL 里各节点的 `CompensateState` **逆序补偿**;
  `forward()` 从失败处向前重试。发起方/Server 重启后可从日志表恢复。
- **DSL**:每个正向 `ServiceTask` 配 `CompensateState`——"每步的补偿动作"在 DSL 里声明式表达。

### 对照表

| 维度 | `aipersimmon` 内核 | Seata SAGA |
| --- | --- | --- |
| step 存储 | 埋在 `data` blob | `seata_state_inst` **每步一行** |
| step 可 SQL 查询 | ❌ | ✅ |
| 补偿声明 | 手写代码 | DSL `CompensateState` + 引擎自动逆序 |
| 正向重试 | 无 | `forward()` |
| 入/出参落库 | 塞 `data` | `input_params`/`output_params` |
| 定义版本化 | 无 | `def.ver` |
| 可观测/运维 | 无 | 三表 + 可视化设计器 |
| 依赖成本 | 零重依赖 | Seata Server + TC + DSL |

## 十、其他开源实现(选型参考)

| 框架 | 形态 | 补齐了本内核的什么 |
| --- | --- | --- |
| **Seata SAGA** | 状态机引擎 + JSON DSL + 三表 | step 落库、声明式补偿、向前/向后恢复 |
| **Eventuate Tram Sagas** | 编排式 + 步骤 DSL + 命令/回复 + outbox | 步骤 DSL、命令派发/回复关联 |
| **Axon Framework** | `@Saga` + associationProperty + SagaStore + DeadlineManager | 注解驱动事件路由、可持久超时 |
| **Temporal / Cadence** | durable execution(workflow-as-code) | 几乎补齐所有缺口(重试/超时/持久/版本/UI) |
| **Camunda 8 / Zeebe** | BPMN 可视化 + 补偿事件 | 图形化编排 + 运维面 |

选型建议见 [[analysis-00007-saga-process-manager]] §四/§七(按信号逐级升级,契约保持引擎无关)。

## 十一、落地建议

1. **多步且需运维观测的 saga**:当前内核偏薄。要么方案 A(`data` 存 JSON + 数据库 JSON 生成列/索引查 step),
   要么方案 B(覆写 `JdbcSagaStore` 建带 `step` 列的表),要么直接上 Seata / Axon。
2. **接收端幂等**:凡消费外部消息的 saga,显式加 `inbox-jdbc`,并在同事务内 `alreadyProcessed`。
3. **发送端幂等**:支付/扣款等透传 `correlationId` 作幂等键给下游网关。
4. **多实例部署**:`JdbcDeadlineScheduler` 当前无租约会重复触发,依赖 handler 幂等;若要精确一次需补 `SKIP LOCKED` 租约(见 §七 B-8)。
5. **升级引擎**:满足 00007 §七信号(小时/天级等待、人工审批、需 BPMN 可视化)时外接 Temporal / Camunda,只换实现不改契约。

## Sources

内部:

- [[analysis-00007-saga-process-manager]] —— saga 设计取舍(是否提供 / 何形态 / 何时升级引擎),本文的上游。
- [[analysis-00006-ddd-building-blocks-library]] —— 构件库纯/脏分离、参考不依赖方法论。
- [[analysis-00001-domain-event-publishing]] —— 事件发布 / outbox(saga 可靠推进底座)。
- [[analysis-00002-domain-vs-integration-events]] —— 跨 BC 集成事件契约。
- 实现源码:`aipersimmon-ddd/aipersimmon-ddd-saga`、`aipersimmon-ddd-saga-spring`、
  scaffold 示例 `aipersimmon-ddd-scaffold-samples/orchestrate-with-saga`、`aipersimmon-ddd/README.md`。

外部:

- Apache Seata, *Saga Mode*. https://seata.apache.org/docs/user/mode/saga/
- Seata saga DDL(`script/client/saga/db/mysql.sql`). https://github.com/apache/incubator-seata/blob/develop/script/client/saga/db/mysql.sql
- Chris Richardson, *Saga pattern*, microservices.io. https://microservices.io/patterns/data/saga.html
- Eventuate Tram Sagas. https://eventuate.io/abouteventuatetram.html
- Temporal —— durable execution / SAGA. https://docs.temporal.io
- Hector Garcia-Molina & Kenneth Salem, *Sagas*, ACM SIGMOD 1987 —— 理论源头。
</content>

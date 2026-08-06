---
id: issue-00066-dead-letter-store-can-replay-but-cannot-be-read
type: issue
status: resolved
blocks: [plan-00015-scaffold-depth-and-evaluability]
---

# `DeadLetterStore` 能重放却不能查询：运维拿不到那个 `eventId`

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

## 问题（现状，file:line 为证）

- **等级：Medium（能力事实上不可用。死信被"妥善留存"，但留存的价值取决于有人能看见它——
  而框架不提供任何看见它的方式）**。
- `aipersimmon-ddd-outbox/.../DeadLetterStore.java` 的全部方法只有两个：
  - `void store(OutboxMessage, int attempts, Reason, String lastError)` —— 由 relay 调用；
  - `boolean replay(String eventId)` —— Javadoc 写着 "Intended for an operator or a support tool
    once the underlying cause is fixed"。
- **但 `eventId` 从哪来？** 没有 `list` / `find` / `count`，也没有任何按时间、类型或原因筛选的入口。
  唯一的获取途径是自己写 SQL 查 `aipersimmon_dead_letter`。
- 实测：plan-00015 F5 在样例里做运维端点时，`POST /ops/dead-letters/{eventId}/replay` 可以直接用
  `replay`，而 `GET /ops/dead-letters` 只能用 `JdbcTemplate` 手写查询
  （`start/.../DeadLetterOpsController.java`），于是样例耦合了一张它不拥有的表的 schema。

## 根因（第一性）

1. **观察 vs 期望**：期望"能重放 ⇒ 能找到要重放的东西"；实际"能重放，但找不到"。
2. **最小机制**：端口是按**写入方**的需要定义的。`store` 与 `replay` 恰好是 relay 和"某个已经知道 id 的人"
   会调用的两个方法；两者都不需要查询，所以查询没有被写进去。
3. **真根因**：死信的价值主张是"给人看"，而这个端口只服务了机器。Javadoc 说它是给 operator 或
   support tool 用的——那句话本身就说明了缺什么：operator 手上没有 id，support tool 也得先列出来。
   一个只能按主键操作、却不提供任何获取主键途径的运维接口，等于把它自己的适用条件排除掉了。
4. **为什么没被库的测试发现**：库的测试自己造消息、自己持有 id，`store` 之后直接 `replay(knownId)`。
   测试永远处在"已经知道 id"这个前提里，而那正是真实运维唯一不成立的前提。
5. **排除的伪根因**：不是后端实现缺失——`aipersimmon_dead_letter` 表的列（`type`、`attempts`、
   `reason`、`last_error`、`failed_at`）完全够支撑一个体面的列表；缺的只是端口上的方法。

## 复现

不是崩溃型缺陷，无法用失败断言复现。最强验证是它带来的实际后果，已固化在样例里：
`DeadLetterOpsController#list` 必须绕过端口手写 SQL，其类注释逐条记录了原因；
`DeadLetterReplayTest` 证明整条"发现 → 重放"的路只有后半段是框架给的。

## 修复

新增**独立的只读端口** `DeadLetters`（`-outbox`），与 `-cqrs` 的 `Slice`/`Cursor` 对齐：

```java
Slice<DeadLetter> list(Cursor after, int size);
Optional<DeadLetter> find(String eventId);
```

四个决定，每个都有取舍：

1. **另立端口，而不是给 `DeadLetterStore` 加方法**。`DeadLetterStore` 的 Javadoc 明确邀请消费方
   替换 bean 去"报警或转发到隔离 topic"——这样的实现**没有可列的东西**，逼它实现 `list` 是错的。
   加抽象方法还会当场打断现有实现方（库内 `OutboxRelayDeadLetterFailureTest` 的
   `FailingDeadLetterStore` 就是一个）。拆开后：存储型后端两个 bean 都注册；转发型实现让读端口缺席，
   于是运维界面**装配失败而不是假装能看**。
2. **`DeadLetter` 是端口自己的只读视图**，不含 `payload`。运维问的是"为什么没送出去"，
   消息体答不了这个问题，而把每条消息体都搬到运维界面既贵又是一种泄漏。
3. **按表自增 `id` 分页，不按 `failed_at`**。id 在"放弃投递"那一刻分配，所以 `id DESC` 已经是
   "最近失败优先"；它唯一（同一毫秒放弃的两行仍有全序），且本身是主键，不需要额外索引。
   cursor 就是这个 id 的字符串形式，对调用方不透明。
4. **`-outbox` 因此新增对 `-cqrs` 的依赖**（仅为 `Slice`/`Cursor`）。取舍是：宁可多一条模块边，
   也不为一个运维列表另造一套分页词汇（`CONTEXT.md` 的"一个概念一个名字"）。两边都 framework-free，
   `-outbox` 的"无 Spring"红线不变，无环。

未做（有意）：批量重放（"把某类型某时间段内的全部重放"）是运维的真实动作，但属于下一步。

## 验证结果

**已修复。**

- `aipersimmon-ddd-outbox`：新增 `DeadLetters`（端口）+ `DeadLetter`（只读视图）。
- 两个后端各一份实现与 bean：`JdbcDeadLetters` / `MybatisDeadLetters`，
  `@ConditionalOnMissingBean(DeadLetters.class)`。
- 库侧测试：`DeadLetterReadTest`（outbox-jdbc，4 项）走完整条运维路径——
  `anOperatorArrivesKnowingNothingAndLeavesHavingRequeuedTheMessage` 明确从"什么都不知道"开始，
  用列表拿到的 id 直接喂给 `replay`，即本 issue 说断掉的那个接缝；另有分页（最近优先、每行只访问一次、
  末页无 cursor）、空表、以及"拒绝什么"（`size<1`、非本端口签发的 cursor）。
  `DeadLetterReadTest`（outbox-mybatis-plus，2 项）钉住两后端**答案一致**。
- 样例侧的绕行代码消失：`DeadLetterMapper` 已删除，`DeadLetterOpsController` 不再持有任何 SQL，
  改为注入两个端口；顺带 `GET /ops/dead-letters/{eventId}` 用上了 `find`，
  列表改为 cursor 分页（`?cursor=&size=`），与 `GET /orders` 同形。
  `DeadLetterReplayTest` 相应改为读 `items` 并新增按 id 取回的断言。
- 附带效果：样例那处 SpotBugs `EI_EXPOSE_REP2` 摩擦（控制器持有注入的 `JdbcTemplate`）随之消失；
  该 finding 现在落在库自己的 `JdbcDeadLetters` 上，按共享过滤器既有约定按类名登记
  （与 `JdbcDeadLetterStore` 等 40 余个同形基础设施 bean 一致）。
- 库 BUILD SUCCESS（773 项，0 失败）；样例 `verify` BUILD SUCCESS（189 项，0 失败）。

## 关联

- [[plan-00015-scaffold-depth-and-evaluability]]（F5；本 issue 在计划阶段即被预登记为"必然会撞上的摩擦点"）

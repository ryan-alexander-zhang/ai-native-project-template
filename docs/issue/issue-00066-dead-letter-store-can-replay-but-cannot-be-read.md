---
id: issue-00066-dead-letter-store-can-replay-but-cannot-be-read
type: issue
role: main
status: open
parent: plan-00015-scaffold-depth-and-evaluability
---

# `DeadLetterStore` 能重放却不能查询：运维拿不到那个 `eventId`

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

## 修复（建议，未实施）

在端口上加一个只读的分页查询，与 `-cqrs` 的 `Slice`/`Cursor` 对齐，例如：

```java
Slice<DeadLetterRecord> list(Cursor after, int size);
Optional<DeadLetterRecord> find(String eventId);
```

`DeadLetterRecord` 应当是端口自己的只读视图（event id、type/version、attempts、reason、
last error、failed at、tenant），而不是把 `OutboxMessage` 直接暴露出去——运维要看的是
"为什么没送出去"，不是消息体本身。

顺带值得考虑：`replay` 目前只按单个 id，批量重放（"把某个类型在某段时间内的全部重放"）是
运维的真实动作，但那属于下一步，不应与本 issue 的最小修复混在一起。

## 验证结果

（未修复。样例以手写 SQL 绕行，并在
`DeadLetterOpsController` 的类注释里指向本 issue。）

## 关联

- [[plan-00015-scaffold-depth-and-evaluability]]（F5；本 issue 在计划阶段即被预登记为"必然会撞上的摩擦点"）

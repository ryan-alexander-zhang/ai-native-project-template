---
id: decision-00018-multi-tenancy-boundaries
type: decision
status: active
parent:
---

# 多租户：隔离模型、传播、唯一键与强制隔离边界

固化 `aipersimmon-ddd` **原生多租户能力**在编码前必须团队背书的决策：用哪种隔离模型、租户如何跨写路径与消息传播、
`tenant_id` 的空值与唯一键约束、以及如何在两类存储后端上强制隔离。承接结构设计 [[design-00009-multi-tenancy-tenant-id]]
（本 ADR 只固化"决策与取舍"，机制细节以 design-00009 为准，不在此重复）。

本 ADR 受既有决策约束：[[decision-00012-no-ambient-per-command-state]]、
[[decision-00013-command-context-and-causation-propagation]]、[[decision-00014-cloudevents-integration-event-contract]]、
[[decision-00016-durable-runtime-staged-message-identity]]、[[design-00005-observability-and-distributed-tracing]]。

## 结论先行

> **采用 pool 模型（共享 schema + `tenant_id` 判别列），不做分库/分 schema（silo 留作 phase 2 可选后端并零成本预留
> seam）。`tenant_id` 恒非空、默认哨兵 `__root__`——单租户即 N=1 的多租户，开关只切解析行为与缺绑定姿态、
> 不改 schema 与代码路径。多租户开启后隔离**fail-closed**：缺绑定即 `MissingTenantException`，
> 决策收口在 `TenantContext.effective()` 一处（命题 13b）；默认的 header 解析器不可信任，须显式 opt-in（命题 13c）。
> 租户是旁路元数据：写侧权威载体是 `CommandContext.tenantId`（显式传值），跨线程/读侧/基础设施才用受限的
> `TenantContext`（请求内单值、trusted-boundary 一次写入、不可变、非开放变量池），跨进程走 `EventEnvelope` 扩展属性 +
> `ce_tenantid` header。租户只进"租户相对"的唯一键（`business_key`、web `idempotency_key` 等），不进框架生成的全局唯一 id
> 去重键。强制隔离：MyBatis-Plus 后端用原生 `TenantLineInnerInterceptor`；PostgreSQL 必须启用 RLS，但只覆盖请求作用域表，
> 消息管道表不建 RLS、后台轮询走 BYPASSRLS 角色；H2/MySQL 退回手工谓词 + ArchUnit 兜底。读侧取"环境租户"方案，
> 不新建 QueryContext。多租户开启却解析不出租户时默认 `REJECT`。**

## Context

design-00009 的四路代码勘察确认框架当前对多租户**零支持**（全树无 tenant / principal / security-context / baggage）。
既有架构的两个事实决定了方案形状：

1. 耐久基础设施（outbox relay、process-manager claim、saga deadline poll）全是**单一全局轮询器**——判别列契合，
   连接路由会逼后台线程按租户分片轮询 + 参数化锁，与现架构对冲。
2. 租户必须像 trace 一样端到端存活。框架已有一套"跨异步中继 + 跨 Kafka 网线"的范式（`StoreAndForwardTracer` 的
   capture/restore + 耐久行 `traceparent/trace_state` 列），租户照抄即可，无需另造机制。

本 ADR 把 design-00009 §十三列出的 6 项取舍固化为决策，并正面处理与 [[decision-00012-no-ambient-per-command-state]]
的张力（见 D5、命题一）。

## Decision

### A. 隔离模型与空值

1. **（pool）判别列，不分库。** 所有耐久表加 `tenant_id`；silo（分库/分 schema）作为 phase 2 可选后端，**现在零成本
   预留** seam：一个读 `TenantContext` 的路由 DataSource 垫在 `JdbcTemplate`/mapper 之下即可，pool→silo 纯增量、
   `tenant_id` 列在 silo 下只是无害冗余。现在不写路由代码。
2. **`tenant_id` 恒非空，哨兵 `__root__`。** 列定义 `NOT NULL DEFAULT '__root__'`；`TenantId` 校验禁止用户租户以
   `__` 开头，结构上杜绝碰撞。**不用 `0`**——真实租户 id 可能恰为 `"0"`，撞上即跨租户泄漏。
3. **单租户 = N=1。** `aipersimmon.ddd.tenancy.enabled`（默认 `false`）切换两件事：解析行为（读请求 / 硬返回
   `__root__`），以及**缺绑定时的姿态**（见命题 13b）。schema、谓词、代码路径恒定。
   **禁止用 NULL 表示"无租户"**（命题二）。
3b. **`tenant_id` 是外部不透明标识，框架不生成、不强制格式，但要求不可变 + 有界长度（建议 ≤32）。**
   **不采用 UUIDv7 作 `tenant_id`**：其时间有序收益只对高基数高频插入的每行 id 成立，`tenant_id` 低基数极少插入，
   杠杆在窄+不可变+作索引前导列（命题四）。UUIDv7/ULID 用于 per-row id 是正交的独立改进，不在本 ADR。

### B. 传播与环境状态边界

4. **写侧权威载体是 `CommandContext.tenantId`。** 新增字段随 `root/deriveChild/of(envelope)` 传播（对齐
   [[decision-00013-command-context-and-causation-propagation]]），发布器从它盖章。跨进程用 `EventEnvelope` 新增
   CloudEvents 扩展属性 + Kafka `ce_tenantid` header（对齐 [[decision-00014-cloudevents-integration-event-contract]]）；
   消费端 `reconstruct` **必须读回** `ce_tenantid`，否则静默丢失。
5. **`TenantContext`（ThreadLocal）受严格约束，与 [[decision-00012-no-ambient-per-command-state]] 不冲突。**
   它**不是**每命令可变变量池：在 trusted boundary（边缘 Filter / 消费入口）**一次写入、请求内不可变、只承载单个
   `TenantId`**，业务代码不得写入。它的用途仅限于（a）边缘→`CommandContext` 的绑定，（b）读侧与基础设施实现的
   隔离谓词来源（那里没有 `CommandContext` 可穿）。租户是"身份"而非"业务状态"，语义等同 operation-log 的 Actor
   "从 trusted boundary 捕获、不从 payload 读"（见命题一）。

### C. schema 与唯一键

6. **租户只进"租户相对"的唯一键。** 判据：自然键跨租户可能合法重复才入键。
   - 入键（正确性/防泄漏所需）：`process_instance(tenant_id, process_type, business_key)`、
     web `idempotency`/`nonce`/`rate_limit` 的 PK。
   - 不入键：框架生成的全局唯一 id（`event_id`、`message_key=ce_id`、`correlation_id`、`transition_id` 等），
     即 owner 决策"租户不进 inbox/outbox 去重键"。
7. **所有表都加 `tenant_id` 数据列**（供 header 盖章 / 观测 / RLS 过滤），是否入键按 D6 区分；`shedlock` 契约表不加。
   web-store-redis 用 key 前缀带租户段。`process_instance` 检索索引以 `tenant_id` 为前导列。

### D. 强制隔离

8. **MyBatis-Plus 后端**：启用原生 `TenantLineInnerInterceptor` + `TenantLineHandler`（租户取自 `TenantContext`），
   自动注入谓词与 INSERT 列，`ignoreTable()` 排除 `shedlock` 与消息管道表。
9. **PostgreSQL 必须启用 RLS**（H2/MySQL 无真 RLS → 手工谓词 + `TenantPredicate` helper）。**RLS 作用域边界**：
   只对请求作用域、租户已知的表建策略（领域表 / 读模型 / `process_instance` 请求侧检索）；**outbox / dead_letter /
   process_effect / process_deadline / inbox 等消息管道表不建 RLS**——由无租户上下文的后台轮询器跨租户全表扫描，
   后台 relay/claim/cleanup 连接使用**独立 BYPASSRLS 角色**，以"tenant 只是行数据、非隔离谓词"方式工作。
10. **ArchUnit** 兜底：标记耐久仓储/查询里未过 `TenantPredicate`、未被 MP 拦截、又非白名单后台轮询的原生 SQL。

### E. 后台轮询与读侧

11. **全局轮询不变。** pool 下租户只是行数据；relay/claim/deadline poll 保持单线程全表扫、单一 ShedLock 锁，
    出站时把行上 `tenant_id` 盖到 `ce_tenantid`。按租户公平调度/配额留作 phase 2。
12. **读侧取"环境租户"方案（design-00009 §九 A）**：读仓储从 `TenantContext.current()` 取租户，不新建 `QueryContext`、
    不给 `QueryBus` 加拦截器链。QueryContext（方案 B）作为将来查询侧需审计/多维元数据时的演进。

### F. 缺租户策略与模块

13. **`MissingTenantPolicy` 默认 `REJECT`。** 多租户开启却解析不出租户时拒绝请求（400/401），绝不悄悄回退共享桶；
    `SYSTEM`（回退哨兵）仅受控内部/迁移场景显式选用。**注意其作用域只到边缘**——它按"一个请求"决策，
    表达不了"基础设施层当前线程没有绑定"，后者由命题 13b 覆盖。
13b. **边缘之下同样 fail-closed，且决策只有一个收口点。**（`issue-00099` 补充；原 ADR 缺此命题，导致 14 处
    调用点各自 `orElse(ROOT)`，`MissingTenantException` 沦为死代码。）凡是盖章或过滤 `tenant_id` 的基础设施
    一律经 `TenantContext.effective()` 取租户：有绑定用之；多租户**关**且无绑定用 `__root__`（N=1，每行本就带哨兵）；
    多租户**开**且无绑定**抛 `MissingTenantException`**。
    理由：哨兵设计本身正确，但它让"没绑定"与"单租户"共用同一个返回值，于是每个局部看 `orElse(ROOT)` 都像对的。
    缺的不是各处判断，而是一个知道**部署处于哪种模式**的收口点——模式是部署级事实，故建模为
    `TenantEnforcement`（框架无关，绑定到 Spring 上下文生命周期），而非按请求传递。
    推论：`TenantContext` 不再提供无条件抛出的 `require()`（与 `effective()` 同概念两名字）;
    跨线程传播由 `TenantContextTaskDecorator` 负责，未绑定即提交时不凭空造租户，交给 `effective()` 响亮失败;
    消费的集成事件在多租户开启时，`ce_tenantid` 升级为必需 CloudEvents 属性（缺失 → 永久失败 → 死信）。
13c. **默认解析器不可被信任，须显式 opt-in。**（`issue-00099` 补充。）`X-Tenant-Id` 由调用方提供，
    框架无任何环节把它与认证主体关联，且租户过滤器排在 Spring Security 之前——信它等于"改一个 header
    就能读写任意租户"。框架**没有可猜的安全默认**（不知道消费方 principal 的形状），故多租户开启 +
    无自定义 `TenantResolver` + 未设 `aipersimmon.ddd.tenancy.trust-header=true` 时**拒绝启动**，
    并由 FailureAnalyzer 把两种安全接法（从认证主体解析 / 由不可绕过的边缘重写该 header）写进启动错误。
14. **新增两个薄模块** `aipersimmon-ddd-tenancy`（framework-free：`TenantId`/`TenantContext`/`TenantResolver`/
    `MissingTenantPolicy`/哨兵）+ `aipersimmon-ddd-tenancy-spring`（边缘 Filter + 绑定 interceptor）。真正的隔离靠改造既有
    组件（design-00009 §十二清单），租户是横切标识，不像 operation-log 能自成一体。

## 命题（易被反问处）

**命题一：`TenantContext` 违反 no-ambient-per-command-state 吗？——不。**
decision-00012 禁的是"业务逻辑在命令执行中途读写的可变变量池"。`TenantContext` 是**身份**，在信任边界一次性解析、
请求内不可变、只含单个 `TenantId`、业务代码只读不写；写路径的权威来源仍是显式传值的 `CommandContext.tenantId`。
这与 operation-log Actor"从 trusted boundary 捕获、不从 payload 读"同构，属于允许的 identity 传播，非被禁的 state 泄漏。

**命题二：单租户为什么也不允许 NULL？**
SQL 唯一约束 NULL≠NULL（PG/MySQL/H2 默认）。含 `tenant_id` 的复合唯一键一旦为 NULL 即被静默架空：跨租户
`business_key` 撞车、`Idempotency-Key` 返回他人缓存响应，且无报错；`WHERE tenant_id=?` 与 RLS 也匹配不到 NULL 行。
哨兵 `__root__` 让单租户与多租户共用一条代码路径，零分支。

**命题三：为什么消息管道表不加 RLS，会不会成为泄漏口？——不会，且必要。**
管道表（outbox/inbox/effect/deadline）承载的是**待投递的消息**，其隔离发生在**投递后**：出站 `ce_tenantid` 携带租户、
消费端据此重建 `TenantContext`，下游领域表/读模型才是 RLS 与谓词把守的边界。管道表若加 RLS，全局轮询器（无租户上下文）
将无法扫到任何行，投递直接瘫痪。故管道表以 BYPASSRLS 角色跨租户扫描是设计必需，而非放松。

**命题四：`tenant_id` 为什么不用 UUIDv7？**
UUIDv7 的价值是时间有序 → 高频插入时 B-tree 页尾追加、避免 v4 随机分裂，只对高基数高频插入的每行 id 成立。
`tenant_id` 低基数、极少插入（新增租户是罕见事件），无插入热点问题；它是众多复合索引的前导列且落在每行，
真正的成本在**宽度**、真正的风险在**可变性**（被嵌进唯一键与已投递的 `ce_tenantid`）。故要求窄+不可变，而非单调。
UUIDv7/ULID 的正确用武之地是框架的 per-row 生成 id（当前为 `UUID.randomUUID()`），属正交的独立改进。

## Consequences

- 正面：单/多租户同一套 schema 与代码；租户随现有 trace 范式端到端存活；MP 后端近乎零业务改动；PG 有 DB 层防漏兜底；
  silo 可后加而不返工。
- 代价：PostgreSQL 部署需管理 `SET LOCAL app.tenant` 与 BYPASSRLS 角色；JDBC(H2/MySQL) 依赖手工谓词 + ArchUnit 纪律；
  消费端 `reconstruct` 与所有耐久行映射都要显式带上租户，遗漏即静默丢失（测试矩阵需覆盖，design-00009 §十四）。

## Alternatives considered

- **silo（分库/分 schema，Axon `axon-multitenancy` 式：每租户独立基础设施段 + 租户走 Message MetaData）**：隔离最强，
  但与本框架单一全局轮询架构对冲、运维重。改列为 phase 2 可选后端，吸收其"租户作为消息旁路元数据"一点。
- **可空 `TenantId` 表示 host 共享数据（.NET ABP 式）**：需全局过滤器专门处理 `IS NULL`，且与我们租户相对键的唯一约束
  冲突（命题二）。否决，改用哨兵 `__root__`。
- **读侧 QueryContext + 查询拦截器链（方案 B）**：与命令侧对称但改动大，MVP 收益不足。降级为演进项。

## 待 spec 明确的运维细节（不阻塞本 ADR）

RLS 的 `SET LOCAL app.tenant` 与连接池（事务边界、连接归还清理）交互；后台 BYPASSRLS 角色的最小权限集；
silo seam 的路由 DataSource bean 形态与 `TenantContext` 生命周期。
